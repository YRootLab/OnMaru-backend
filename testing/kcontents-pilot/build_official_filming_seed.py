#!/usr/bin/env python3
"""Build a reviewable #691 seed from a pinned public filming-location CSV and catalog export.

Inputs are intentionally explicit and read-only. Generated SQL defaults to ROLLBACK.
"""
import argparse
import csv
import hashlib
import json
import re
from pathlib import Path

DATASET_SHA256 = '1eba00010a3274e58a302d3dfdf0b7e56b4d7af3fe03680edfeae9866845ce3c'
DATASET_URL = 'https://www.data.go.kr/data/15111405/fileData.do'
CATEGORIES = {'HANOK', 'HANOK_CAFE', 'HANOK_EXPERIENCE', 'HANOK_STAY', 'HISTORIC_SITE', 'TRADITIONAL_MARKET'}
# These summaries were drafted with Codex and checked against the cited row. Each
# describes one scene or setting; no release year, genre, image rights, or credits are inferred.
REVIEWED = {
 '1015': ('중앙고등학교가 등장인물들이 다니는 학교로 나온다.', '학교'),
 '1232': ('첫 회에 이동식의 동생 유연이 등장하는 성당으로 나온다.', '성당'),
 '1383': ('1회에 사나희가 연 베이커리 카페로 등장한다.', '카페'),
 '1511': ('1회에 최웅과 국연수의 학교 장면을 촬영한 장소다.', '학교'),
 '1997': ('8회에 염기정이 조태훈을 만나는 카페로 나온다.', '카페'),
 '2033': ('3회에 마동찬과 나하영이 다시 만나는 장소로 나온다.', '재회'),
 '3012': ('13회에 이곤이 정태을을 기다리는 장소로 나온다.', '기다림'),
 '3110': ('지은탁이 다니는 학교로 나온다.', '학교'),
 '3147': ('14회 회상 장면에서 선우준이 다닌 학교로 나온다.', '학교'),
 '3908': ('15회에 박해영과 오윤주가 만나는 장면에 등장한다.', '만남'),
 '6803': ('정복기가 차주은을 만나러 시장 골목을 걷는 장면에 등장한다.', '시장'),
 '9929': ('14회에 차주혁이 이혜원을 우연히 만나는 카페로 나온다.', '카페'),
 '10641': ('1회에 임주경이 전학 온 학교로 나온다.', '학교'),
 '11100': ('완득이가 폭력배와 충돌하는 장소로 나온다.', '시장'),
 '11197': ('등장인물이 인사하러 다니는 전통시장으로 나온다.', '시장'),
 '11512': ('3회 야간 학교 수련회 장소로 나온다.', '수련회'),
 '11659': ('조이서와 장근원이 만나는 카페로 나온다.', '카페'),
 '12170': ('7회에 전녹두와 동동주가 소풍을 가는 정자로 나온다.', '소풍'),
 '12205': ('1회에 김조조, 이혜영, 황순오가 다니는 학교로 나온다.', '학교'),
 '12830': ('최한성이 사는 집으로 나온다.', '집'),
 '13431': ('4회에 윤소아와 하백이 사복을 사러 가는 시장으로 나온다.', '시장'),
 '13472': ('유리의 어머니가 다니는 절로 나온다.', '사찰'),
 '13513': ('김미경과 주서연이 교사로 일하는 학교로 나온다.', '학교'),
}

def norm(value):
    return re.sub(r'[^0-9가-힣a-z]', '', value.lower())

def quote(value):
    return "'" + value.replace("'", "''") + "'"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--csv', type=Path, required=True)
    parser.add_argument('--matches', type=Path, required=True)
    parser.add_argument('--places', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert hashlib.sha256(args.csv.read_bytes()).hexdigest() == DATASET_SHA256, 'Dataset checksum changed'
    with args.csv.open(encoding='cp949', newline='') as stream:
        source = {row['연번']: row for row in csv.DictReader(stream)}
    matches = {m['sourceRow']: m for m in map(json.loads, args.matches.read_text().splitlines())}
    places = {p['placeId']: p for p in map(json.loads, args.places.read_text().splitlines())}
    rows = []
    for key, (summary, tag) in REVIEWED.items():
        m, s = matches[key], source[key]
        p = places[m['placeId']]
        assert m['grade'] == 'EXACT' and m['distanceM'] <= 30 and m['category'] in CATEGORIES
        assert m['mediaType'] in ('drama', 'movie')
        assert norm(s['장소명']) == norm(p['title']) and norm(s['주소']) == norm(p['address'])
        assert s['장소설명'] and s['제목'] == m['work'] and s['장소설명'] == m['sourceDescription']
        assert len(summary) <= 500 and tag in ('학교','카페','시장','성당','집','사찰','기다림','수련회','만남','재회','소풍')
        rows.append((key, s, p, summary, tag))
    assert len(rows) == len(REVIEWED)
    out = ["-- Issue #691: official dataset / Codex-assisted scene summaries and tag candidates.",
           "-- Dataset: " + DATASET_URL, "-- SHA256: " + DATASET_SHA256,
           "-- Dry run by default. Apply only with -v apply=1 after reviewing SELECT output.",
           "\\set ON_ERROR_STOP on", "BEGIN;", "SET LOCAL statement_timeout = '30s';", "SET LOCAL lock_timeout = '3s';",
           "CREATE TEMP TABLE reviewed(row_no text PRIMARY KEY, place_id uuid, content_id text, fingerprint text, work_title text, work_type text, normalized_title text, source_place text, source_address text, source_excerpt text, summary text, tag text) ON COMMIT DROP;",
           "INSERT INTO reviewed VALUES"]
    values = []
    for key, s, p, summary, tag in rows:
        fields = [key, p['placeId'], p['contentId'], p['sourceFingerprint'], s['제목'], 'DRAMA' if s['미디어타입']=='drama' else 'MOVIE', norm(s['제목']), s['장소명'], s['주소'], s['장소설명'], summary, tag]
        values.append('(' + ','.join(map(quote, fields)) + ')')
    out.append(',\n'.join(values) + ';')
    out += [f"DO $$ BEGIN IF (SELECT count(*) FROM reviewed) <> {len(rows)} THEN RAISE EXCEPTION 'reviewed row count changed'; END IF;",
            "IF (SELECT count(*) FROM reviewed r JOIN onmaru.catalog_kto_korean_content_versions s ON s.contentid=r.content_id AND s.raw_hash=r.fingerprint JOIN onmaru.catalog_active_datasets a ON a.dataset='kto-korean-tour' AND a.revision_id=s.revision_id JOIN onmaru.catalog_place_versions p ON p.revision_id=s.revision_id AND p.source_ref_id=s.source_ref_id AND p.place_id=r.place_id AND p.status='ACTIVE') <> (SELECT count(*) FROM reviewed) THEN RAISE EXCEPTION 'Active catalog identity or fingerprint changed'; END IF;",
            "IF EXISTS (SELECT 1 FROM reviewed r JOIN onmaru.k_contents w ON w.work_type=r.work_type AND w.normalized_title=r.normalized_title WHERE w.id<>md5('onmaru:official691:work:'||r.work_type||':'||r.normalized_title)::uuid) THEN RAISE EXCEPTION 'Existing work identity requires reconciliation'; END IF; END $$;",
            "INSERT INTO onmaru.k_contents(id,title,normalized_title,work_type,status,verified_by,verified_at,rule_version) SELECT DISTINCT md5('onmaru:official691:work:'||work_type||':'||normalized_title)::uuid,work_title,normalized_title,work_type,'AUTO_VERIFIED','codex-official-dataset-691',now(),'official-filming-exact-v1' FROM reviewed ON CONFLICT(id) DO NOTHING;",
            "INSERT INTO onmaru.k_content_metadata_sources(id,k_content_id,canonical_url,source_type,title,publisher,excerpt,document_location,observed_at,status,verified_by,verified_at,rule_version) SELECT DISTINCT ON (work_type,normalized_title) md5('onmaru:official691:metadata:'||work_type||':'||normalized_title)::uuid,md5('onmaru:official691:work:'||work_type||':'||normalized_title)::uuid,'"+DATASET_URL+"','OFFICIAL','한국문화정보원_미디어콘텐츠 영상 촬영지 데이터','한국문화정보원',source_excerpt,'CSV 연번 '||row_no,now(),'VERIFIED','codex-official-dataset-691',now(),'official-filming-exact-v1' FROM reviewed ORDER BY work_type,normalized_title,row_no ON CONFLICT(k_content_id,canonical_url) DO NOTHING;",
            "INSERT INTO onmaru.k_content_place_relations(id,place_id,k_content_id,relation_type,status,verified_by,verified_at,rule_version,model_version) SELECT md5('onmaru:official691:relation:'||row_no)::uuid,place_id,md5('onmaru:official691:work:'||work_type||':'||normalized_title)::uuid,'FILMING_LOCATION','AUTO_VERIFIED','codex-official-dataset-691',now(),'official-filming-exact-v1','codex-assisted-2026-10-12' FROM reviewed ON CONFLICT(place_id,k_content_id,relation_type) DO NOTHING;",
            "INSERT INTO onmaru.k_content_relation_evidence(id,relation_id,canonical_url,source_type,title,publisher,filming_excerpt,document_location,observed_at,status,verified_by,verified_at,rule_version) SELECT md5('onmaru:official691:evidence:'||row_no)::uuid,md5('onmaru:official691:relation:'||row_no)::uuid,'"+DATASET_URL+"','OFFICIAL','한국문화정보원_미디어콘텐츠 영상 촬영지 데이터','한국문화정보원',source_excerpt,'CSV 연번 '||row_no,now(),'VERIFIED','codex-official-dataset-691',now(),'official-filming-exact-v1' FROM reviewed ON CONFLICT(relation_id,canonical_url) DO NOTHING;",
            "INSERT INTO onmaru.k_content_relation_summary_points(id,relation_id,position,summary_text,evidence_id,status,generated_at,prompt_version,source_fingerprint,edited_by) SELECT md5('onmaru:official691:summary:'||row_no)::uuid,md5('onmaru:official691:relation:'||row_no)::uuid,1,summary,md5('onmaru:official691:evidence:'||row_no)::uuid,'VERIFIED',now(),'official-scene-summary-v1',encode(sha256(convert_to(source_excerpt,'UTF8')),'hex'),'codex-issue-691' FROM reviewed ON CONFLICT(relation_id,position) DO NOTHING;",
            "INSERT INTO onmaru.k_content_tag_candidates(id,scope,relation_id,evidence_id,raw_label,normalized_label,group_hint,supporting_quote,model_version,status) SELECT md5('onmaru:official691:tag:'||row_no)::uuid,'RELATION_TAG',md5('onmaru:official691:relation:'||row_no)::uuid,md5('onmaru:official691:evidence:'||row_no)::uuid,tag,tag,'RELATION_CONTEXT',source_excerpt,'codex-assisted-2026-10-12','REVIEW_REQUIRED' FROM reviewed ON CONFLICT DO NOTHING;",
            "SELECT count(*) AS reviewed_rows,count(DISTINCT place_id) AS places,count(DISTINCT work_type||':'||normalized_title) AS works FROM reviewed;",
            "SELECT r.row_no,r.work_title,r.source_place,r.summary,r.tag FROM reviewed r ORDER BY r.row_no;",
            "\\if :{?apply}", "COMMIT;", "\\else", "ROLLBACK;", "\\endif"]
    args.output.write_text('\n'.join(out)+'\n')
    print(f'{len(rows)} reviewed relations, {len({r[2]["placeId"] for r in rows})} places -> {args.output}')

if __name__ == '__main__': main()
