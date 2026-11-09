import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();

function read(path) {
  return readFileSync(join(root, path), 'utf8');
}

function readJson(path) {
  return JSON.parse(read(path));
}

describe('PostgreSQL backup and restore drill', () => {
  it('defines encrypted backup, isolated restore, deletion replay, and measured recovery gates', () => {
    const policy = readJson('infra/backup/policy.json');
    const dockerfile = read('infra/backup/Dockerfile');
    const createBackup = read('infra/backup/bin/create-backup');
    const restoreDrill = read('infra/backup/bin/restore-drill');
    const replay = read('infra/backup/sql/replay-deletion-ledger.sql');
    const integrity = read('infra/backup/sql/integrity.sql');

    assert.equal(policy.backup.format, 'POSTGRES_CUSTOM_LOGICAL_FULL');
    assert.equal(policy.backup.retentionDays, 7);
    assert.equal(policy.recovery.rpoTargetSeconds, 86400);
    assert.equal(policy.recovery.rtoTargetSeconds, 14400);
    assert.equal(policy.encryption.required, true);
    assert.equal(policy.encryption.algorithm, 'GPG_AES256');
    assert.equal(policy.storage.separateFromApplicationDatabase, true);
    assert.equal(policy.credentials.backupRole, 'onmaru_backup');
    assert.equal(policy.pitr.status, 'UNAVAILABLE_UNTIL_HOSTING_SELECTED');

    assert.match(dockerfile, /postgres:17-alpine/);
    assert.match(dockerfile, /gnupg/);
    assert.match(dockerfile, /jq/);
    assert.match(dockerfile, /chmod -R a\+rX \/opt\/onmaru\/backup\/sql/);
    assert.match(dockerfile, /USER postgres/);

    assert.match(createBackup, /pg_dump/);
    assert.match(createBackup, /--format=custom/);
    assert.match(createBackup, /pg_export_snapshot\(\)/);
    assert.match(createBackup, /--snapshot="\$snapshot_id"/);
    assert.match(createBackup, /SET TRANSACTION SNAPSHOT :'snapshot_id'/);
    assert.match(createBackup, /gpg[\s\S]+--symmetric/);
    assert.match(createBackup, /--passphrase-file/);
    assert.match(createBackup, /ONMARU_BACKUP_KEY_FILE/);
    assert.match(createBackup, /pg_has_role\(current_user, 'onmaru_backup', 'member'\)/);
    assert.match(createBackup, /trap/);
    assert.doesNotMatch(createBackup, /echo ["']?\$PGPASSWORD/);

    assert.match(restoreDrill, /ONMARU_RESTORE_CONFIRM/);
    assert.match(restoreDrill, /isolated-target/);
    assert.match(restoreDrill, /sourceFingerprint/);
    assert.match(restoreDrill, /verify_artifact_checksum/);
    assert.match(restoreDrill, /actual_digest/);
    assert.match(restoreDrill, /expected_name/);
    assert.match(restoreDrill, /pg_class/);
    assert.match(restoreDrill, /pg_namespace/);
    assert.match(restoreDrill, /pg_extension/);
    assert.match(restoreDrill, /c\.relkind IN \('r', 'p', 'v', 'm', 'S', 'f'\)/);
    assert.match(restoreDrill, /pg_restore[\s\S]+--exit-on-error/);
    assert.match(restoreDrill, /replay-deletion-ledger\.sql/);
    assert.match(restoreDrill, /integrity\.sql/);
    assert.match(restoreDrill, /ONMARU_DELETION_LEDGER_ARTIFACT/);
    assert.match(restoreDrill, /ONMARU_DELETION_LEDGER_KEY_FILE/);
    assert.match(restoreDrill, /gpg[\s\S]+--decrypt/);
    assert.match(restoreDrill, /rpoSeconds/);
    assert.match(restoreDrill, /rtoSeconds/);
    assert.ok(
      restoreDrill.indexOf('restore_started_epoch=')
        < restoreDrill.indexOf('artifact_sha=$(verify_artifact_checksum'),
      'RTO measurement must include checksum verification and decryption',
    );

    assert.match(replay, /identity_member_status[\s\S]*DELETING|status = 'DELETING'/);
    assert.match(replay, /identity_sessions[\s\S]+revoked_at/);
    assert.match(replay, /discovery_runs[\s\S]+CANCELLED/);
    assert.match(replay, /community_visit_reviews[\s\S]+HIDDEN/);

    for (const metric of [
      'countMismatches',
      'unvalidatedForeignKeys',
      'invalidSavedSnapshotHashes',
      'deletionReexposureCount',
    ]) {
      assert.match(integrity, new RegExp(metric));
    }
  });

  it('runs a two-database drill and preserves sanitized evidence in CI', () => {
    const drill = read('infra/backup/test/run-restore-drill');
    const workflow = read('.github/workflows/backup-restore-drill.yml');
    const runbook = read('docs/operations/runbooks/restore.md');
    const operationsIndex = read('docs/operations/README.md');

    assert.match(drill, /docker network create/);
    assert.match(drill, /source_container/);
    assert.match(drill, /target_container/);
    assert.match(drill, /ONMARU_POSTGIS_PLATFORM/);
    assert.match(drill, /--platform/);
    assert.match(drill, /postgis\/postgis:17-3\.5-alpine/);
    assert.match(drill, /PostGIS_Version/);
    assert.match(drill, /docker build --pull=false/);
    assert.match(drill, /PostgreSQL init process complete/);
    assert.match(drill, /dropdb/);
    assert.match(drill, /--template=template0/);
    assert.match(drill, /onmaru_backup_login/);
    assert.match(drill, /create-backup/);
    assert.match(drill, /restore-drill/);
    assert.match(drill, /checksum sidecar accepted a different artifact name/);
    assert.match(drill, /restore accepted a non-empty user schema/);
    assert.match(drill, /ONMARU_ARTIFACT_NAME="\$artifact_name"/);
    assert.match(drill, /sidecar="\/work\/backups\/\$ONMARU_ARTIFACT_NAME\.sha256"/);
    assert.match(drill, /mv "\$sidecar" "\$sidecar\.valid"/);
    assert.match(drill, /mv "\$sidecar\.valid" "\$sidecar"/);
    assert.doesNotMatch(drill, /cp "\$artifact\.sha256"/);
    assert.match(drill, /\.tar\.gpg/);
    assert.match(
      drill,
      /artifact_name[\s\S]+-v "\$evidence_dir:\/evidence"[\s\S]+ONMARU_RESTORE_EVIDENCE_DIR=\/evidence/,
    );
    assert.match(drill, /deletionReexposureCount[\s\S]+0/);
    assert.match(drill, /trap/);

    assert.match(workflow, /name: PostgreSQL Restore Drill/);
    assert.match(workflow, /workflow_dispatch:/);
    assert.match(workflow, /permissions:\n\s+contents: read/);
    assert.match(workflow, /infra\/backup\/test\/run-restore-drill/);
    assert.match(workflow, /actions\/upload-artifact@v4/);
    assert.match(workflow, /retention-days: 7/);
    assert.doesNotMatch(workflow, /contents: write|pull-requests: write/);

    for (const requiredText of [
      'RPO 24시간',
      'RTO 4시간',
      'isolated-target',
      'onmaru_backup',
      'deletion ledger',
      'PITR',
      'UNAVAILABLE',
    ]) {
      assert.match(runbook, new RegExp(requiredText));
    }
    assert.match(operationsIndex, /runbooks\/restore\.md/);
  });

  it('restores the real production artifact with isolated target and operation gates', () => {
    const drill = read('infra/backup/test/run-production-restore-drill');
    const runbook = read('docs/operations/runbooks/restore.md');
    const evidenceIndex = read('docs/operations/release-evidence/backup/README.md');

    for (const requiredVariable of [
      'ONMARU_PRODUCTION_BACKUP_DIR',
      'ONMARU_BACKUP_KEY_FILE',
      'ONMARU_DELETION_LEDGER_ARTIFACT',
      'ONMARU_DELETION_LEDGER_KEY_FILE',
    ]) {
      assert.match(drill, new RegExp(requiredVariable));
    }
    assert.match(
      drill,
      /must contain exactly one onmaru-\*\.tar\.gpg/,
      'the drill must pin the evidence to exactly one production artifact',
    );
    assert.match(drill, /checksum sidecar is missing/);
    assert.match(drill, /ONMARU_RESTORE_CONFIRM=isolated-target/);
    assert.match(drill, /"\$image" restore-drill/);
    assert.doesNotMatch(
      drill,
      /source_container/,
      'the production drill must not spin up a synthetic source database',
    );
    assert.match(drill, /--memory "\$target_mem_limit"/);
    assert.match(drill, /flyway_schema_history/);
    assert.match(drill, /pg_extension WHERE extname = 'postgis'/);
    assert.match(drill, /onmaru\.identity_members/);
    assert.match(drill, /PRODUCTION_BACKUP_ISOLATED_RESTORE/);
    assert.doesNotMatch(
      drill,
      /cat "\$ONMARU_BACKUP_KEY_FILE"|echo "\$PGPASSWORD"/,
      'the drill must never print key or credential material',
    );

    assert.match(runbook, /Production backup 격리 복원 증적/);
    assert.match(runbook, /run-production-restore-drill/);
    assert.match(runbook, /release-evidence\/backup\//);
    assert.match(evidenceIndex, /PRODUCTION_BACKUP_ISOLATED_RESTORE/);
    assert.match(evidenceIndex, /secret/);
  });

  it('keeps the synthetic CI drill independent from the production artifact drill', () => {
    const drill = read('infra/backup/test/run-restore-drill');
    const productionDrill = read('infra/backup/test/run-production-restore-drill');

    assert.match(drill, /synthetic-backup-key/);
    assert.doesNotMatch(productionDrill, /synthetic-backup-key/);
  });
});
