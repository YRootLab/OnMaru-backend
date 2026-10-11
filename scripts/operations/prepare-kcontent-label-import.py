#!/usr/bin/env python3
"""Generate a transactional psql import for a verified local label batch.

The emitted SQL defaults to ROLLBACK. Run against a migrated DB only after review.
"""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import uuid


def sql_literal(value):
    if value is None:
        return 'NULL'
    return "'" + str(value).replace("'", "''") + "'"


def validate(labels, manifest):
    if len(labels) != manifest['uniqueContentIds']:
        raise ValueError('label count differs from manifest')
    ids = [row['contentId'] for row in labels]
    if len(set(ids)) != len(ids):
        raise ValueError('duplicate contentId in labels')
    if ids != sorted(ids, key=lambda value: (len(value), value)):
        raise ValueError('labels are not in deterministic contentId order')
    digest = hashlib.sha256('\n'.join(
        row['contentId'] + ':' + row['sourceHash'] + ':' + row['decision'] for row in labels
    ).encode()).hexdigest()
    if digest != manifest['labelSetSha256']:
        raise ValueError('label digest differs from manifest')
    if sum(manifest['decisions'].values()) != len(labels):
        raise ValueError('decision counts differ from manifest')
    if any(row['policyVersion'] != manifest['policyVersion'] for row in labels):
        raise ValueError('mixed policy versions')
    return uuid.uuid5(uuid.NAMESPACE_URL, 'onmaru:kcontent-label:' + digest)


def generate(labels, manifest, apply=False, publish=False):
    batch_id = validate(labels, manifest)
    if publish and (not manifest.get('verifiedRelationsProvided') or not manifest.get('officialSourceRows')):
        raise ValueError('official and existing verified sources are required for publication')
    if publish and manifest.get('verifiedOutsideCandidateContentIds'):
        raise ValueError('verified places outside selected capture block publication')
    output = io.StringIO()
    output.write('\\set ON_ERROR_STOP on\nBEGIN;\nSET LOCAL statement_timeout = \'120s\';\n')
    output.write('CREATE TEMP TABLE import_labels(label jsonb NOT NULL) ON COMMIT DROP;\n')
    output.write('COPY import_labels(label) FROM STDIN WITH (FORMAT csv);\n')
    writer = csv.writer(output, lineterminator='\n')
    for row in labels:
        writer.writerow([json.dumps(row, ensure_ascii=False, separators=(',', ':'))])
    output.write('\\.\n')
    output.write(f"""
INSERT INTO onmaru.k_content_label_batches
  (id,captured_at,policy_version,expected_count,label_set_sha256,official_source_sha256,status)
VALUES ({sql_literal(batch_id)}::uuid,{sql_literal(manifest['capturedAt'])}::timestamptz,
        {sql_literal(manifest['policyVersion'])},{len(labels)},
        {sql_literal(manifest['labelSetSha256'])},{sql_literal(manifest.get('officialSourceSha256'))},'STAGED')
ON CONFLICT (id) DO NOTHING;
INSERT INTO onmaru.k_content_candidate_labels
  (batch_id,content_id,source_hash,decision,reason_code,role,research_status,label)
SELECT {sql_literal(batch_id)}::uuid,label->>'contentId',label->>'sourceHash',
       label->>'decision',label->>'reason',NULLIF(label->>'role',''),
       label->>'kcontentsResearchStatus',label
FROM import_labels
ON CONFLICT (batch_id,content_id) DO NOTHING;
DO $check$ BEGIN
 IF (SELECT count(*) FROM onmaru.k_content_candidate_labels WHERE batch_id={sql_literal(batch_id)}::uuid) <> {len(labels)}
    OR (SELECT label_set_sha256 FROM onmaru.k_content_label_batches WHERE id={sql_literal(batch_id)}::uuid)
       <> {sql_literal(manifest['labelSetSha256'])}
    OR (SELECT encode(sha256(convert_to(string_agg(
         content_id || ':' || source_hash || ':' || decision, E'\\n'
         ORDER BY length(content_id),content_id),'UTF8')),'hex')
        FROM onmaru.k_content_candidate_labels WHERE batch_id={sql_literal(batch_id)}::uuid)
       <> {sql_literal(manifest['labelSetSha256'])}
    OR EXISTS (SELECT 1 FROM import_labels staged
               JOIN onmaru.k_content_candidate_labels stored
                 ON stored.batch_id={sql_literal(batch_id)}::uuid
                AND stored.content_id=staged.label->>'contentId'
               WHERE stored.label <> staged.label)
 THEN RAISE EXCEPTION 'K-Contents label import integrity mismatch'; END IF;
END $check$;
""")
    if publish:
        output.write(f"""
UPDATE onmaru.k_content_label_batches SET status='PUBLISHED',published_at=now()
WHERE id={sql_literal(batch_id)}::uuid AND status='STAGED';
INSERT INTO onmaru.k_content_label_active(singleton,batch_id,activated_at)
VALUES (true,{sql_literal(batch_id)}::uuid,now())
ON CONFLICT(singleton) DO UPDATE SET batch_id=EXCLUDED.batch_id,activated_at=EXCLUDED.activated_at;
""")
    output.write('COMMIT;\n' if apply else 'ROLLBACK;\n')
    return output.getvalue(), batch_id


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('labels')
    parser.add_argument('manifest')
    parser.add_argument('--output', required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--publish', action='store_true')
    args = parser.parse_args()
    labels = [json.loads(line) for line in Path(args.labels).read_text().splitlines()]
    manifest = json.loads(Path(args.manifest).read_text())
    script, batch_id = generate(labels, manifest, args.apply, args.publish)
    Path(args.output).write_text(script)
    Path(args.output).chmod(0o600)
    print(json.dumps({'batchId': str(batch_id), 'rows': len(labels), 'apply': args.apply,
                      'publish': args.publish}))


if __name__ == '__main__':
    main()
