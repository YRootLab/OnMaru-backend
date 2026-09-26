# DataLab 지역 원천 코드 등록 runbook

DataLab 방문자 수집은 `catalog_region_source_codes`의 값을 추측하거나 FE 지역명을 변환해 호출하지 않는다. `KTO_DATALAB` / `visitor` mapping은 해당 수집 기준일에 유효하고, `catalog_datalab_region_mappings`에서 `ACTIVE`이며 공식 provenance가 완전한 경우에만 JDBC registry에 노출된다.

수집 요청 자체는 전국 응답을 받지만 게시 범위는 공식 검증된 Catalog mapping으로 제한한다. 활성 mapping이 없거나 응답에서 활성 mapping 중 하나라도 누락되면 새 snapshot을 게시하지 않고 이전 active revision을 유지한다.

## 등록 전 확인

등록자는 공식 DataLab API 문서 또는 코드표의 HTTPS URL을 확보하고 다음을 확인한다.

- 원천 code와 OnMaru `catalog_regions.code`가 같은 행을 뜻하는지
- provider, dataset, code, 유효 시작일과 종료일
- 코드표를 확인한 시각과 작업자 식별자

블로그, 검색 결과, 프론트엔드 fixture, 추론한 행정코드는 공식 검증 근거가 될 수 없다. 실제 code를 이 문서나 migration에 예시로 추가하지 않는다.

## 2026-09-26 검증 완료 매핑

아래 네 mapping은 한국관광공사 **지역별 방문자수_GW**의 공식 문서와 인증된 전국 응답을 함께 대조해 검증했다. DataLab의 코드체계는 OnMaru의 법정동 기반 `kr-*` 코드와 같지 않으므로 숫자 일부가 달라도 변환하거나 합산하지 않는다.

| OnMaru Catalog 코드 | DataLab source code | 대조한 응답 지역명 | 근거 |
| --- | --- | --- | --- |
| `kr-11` | `SIDO:11` | 서울특별시 | v4.1 매뉴얼 예시 및 전국 시도 응답 |
| `kr-11-jongno` | `SIGUNGU:11110` | 종로구 | v4.1 매뉴얼 예시 및 전국 시군구 응답 |
| `kr-45` | `SIDO:52` | 전북특별자치도 | 2025-09-20 전국 시도 응답 |
| `kr-45-jeonju` | `SIGUNGU:52110` | 전주시 | 2025-09-20 전국 시군구 응답 |

공식 등록 페이지는 [한국관광공사_빅데이터_지역별 방문자수_GW](https://www.data.go.kr/data/15101972/openapi.do)이며, 요청·대조 시각은 `2026-09-26T09:00:00+09:00`이다. `V025`는 원천 mapping과 검증 이력을 만들고, `V027`은 전용 registry를 추가해 이미 있는 활성 지역을 `ACTIVE`로 backfill한다. 같은 DataLab source code가 다른 지역에 이미 연결돼 있으면 덮어쓰지 않는다.

## Registry 상태와 게시 정책

- `PENDING`: 공식 코드와 계층을 검토 중이다. provider 요청과 게시에서 제외한다.
- `ACTIVE`: HTTPS 공식 출처, 원천 관측 시각, 검증자, 검증 시각이 모두 있고 계층 구조가 유효하다. 이 상태만 수집한다.
- `REJECTED`: 잘못되었거나 폐기한 후보이다. provider 요청과 게시에서 제외한다.

같은 기간의 `region_id`와 `source_code`는 각각 유일해야 하며, 종료된 mapping 뒤에는
겹치지 않는 새 이력을 등록할 수 있다. `ACTIVE` provenance는 같은 source mapping의 공식
verification URL·검증자·검증 시각과 정확히 일치해야 한다. `SIDO:*`는 최상위 SIDO,
`SIGUNGU:*`는 SIDO 부모를 둔 SIGUNGU에만 연결할 수 있다. provider 응답의 scope가
다르거나, 활성 mapping의 응답이 누락되거나 중복되거나, 값이 잘못되면 batch 전체를
quarantine한다. 새 revision은 게시하지 않으며 직전 active revision을 유지한다. provider가
`null`을 반환한 경우에는 `0`으로 바꾸지 않고 `NOT_AVAILABLE` 관측으로 보존한다.

## 안전한 등록 SQL 템플릿

아래의 `<...>`는 실행 전에 검증한 값으로 바꾼다. template은 신규 mapping 전용이며, 기존 mapping을 자동 갱신하거나 다른 지역으로 재지정하지 않는다. 중복이면 transaction을 중단하고 변경 이력을 검토한다.

```sql
BEGIN;

-- 1. 내부 지역이 활성 상태인지 먼저 확인한다. 결과가 정확히 1행이어야 한다.
SELECT id, code, name
FROM onmaru.catalog_regions
WHERE code = '<ONMARU_REGION_CODE>'
  AND active;

-- 2. 같은 provider/dataset/source code의 기존 유효기간을 검토한다.
SELECT source_code, valid_from, valid_to, region_id
FROM onmaru.catalog_region_source_codes
WHERE provider = 'KTO_DATALAB'
  AND dataset = 'visitor'
  AND source_code = '<OFFICIAL_DATALAB_REGION_CODE>'
ORDER BY valid_from DESC;

-- 3. 신규 mapping을 등록한다. 이미 있으면 실패해야 한다.
INSERT INTO onmaru.catalog_region_source_codes (
    provider, dataset, source_code, valid_from, valid_to, region_id
)
SELECT
    'KTO_DATALAB',
    'visitor',
    '<OFFICIAL_DATALAB_REGION_CODE>',
    DATE '<VALID_FROM>',
    NULL, -- 종료된 코드는 DATE '<VALID_TO>'로 명시한다.
    id
FROM onmaru.catalog_regions
WHERE code = '<ONMARU_REGION_CODE>'
  AND active;

-- 4. 공식 HTTPS 근거가 없으면 이 insert를 실행하지 않는다.
INSERT INTO onmaru.catalog_region_source_code_verifications (
    provider, dataset, source_code, valid_from,
    official_source_url, verified_at, verified_by
) VALUES (
    'KTO_DATALAB',
    'visitor',
    '<OFFICIAL_DATALAB_REGION_CODE>',
    DATE '<VALID_FROM>',
    '<OFFICIAL_HTTPS_CODEBOOK_OR_API_DOCUMENT_URL>',
    now(),
    '<OPERATOR_IDENTIFIER>'
);

-- 5. 검토가 끝난 mapping만 ACTIVE로 등록한다. 원천 관측 시각은 검증 시각과 별개다.
INSERT INTO onmaru.catalog_datalab_region_mappings (
    provider, dataset, source_code, valid_from, valid_to, region_id, level, name,
    source_url, source_observed_at, verified_by, verified_at, status
)
SELECT
    source.provider, source.dataset, source.source_code, source.valid_from, source.valid_to,
    source.region_id, '<SIDO_OR_SIGUNGU>', '<OFFICIAL_PROVIDER_REGION_NAME>',
    verification.official_source_url, TIMESTAMPTZ '<SOURCE_OBSERVED_AT>',
    verification.verified_by, verification.verified_at, 'ACTIVE'
FROM onmaru.catalog_region_source_codes source
JOIN onmaru.catalog_region_source_code_verifications verification
  ON verification.provider = source.provider
 AND verification.dataset = source.dataset
 AND verification.source_code = source.source_code
 AND verification.valid_from = source.valid_from
WHERE source.provider = 'KTO_DATALAB'
  AND source.dataset = 'visitor'
  AND source.source_code = '<OFFICIAL_DATALAB_REGION_CODE>'
  AND source.valid_from = DATE '<VALID_FROM>';

COMMIT;
```

## 변경·폐기와 확인

코드가 변경된 경우 기존 row의 `valid_to`를 운영 검토로 종료한 뒤, 새 `valid_from` row와 별도 검증 이력을 추가한다. 기존 source code를 다른 지역으로 update하지 않는다. 수집 전에 다음 query가 정확한 code만 반환하는지 검증한다.

```sql
SELECT region.code, mapping.source_code, mapping.level, mapping.name,
       mapping.status, mapping.source_url, mapping.source_observed_at,
       mapping.verified_by, mapping.verified_at
FROM onmaru.catalog_datalab_region_mappings mapping
JOIN onmaru.catalog_region_source_codes source
  ON source.provider = mapping.provider
 AND source.dataset = mapping.dataset
 AND source.source_code = mapping.source_code
 AND source.valid_from = mapping.valid_from
JOIN onmaru.catalog_regions region ON region.id = source.region_id
WHERE mapping.provider = 'KTO_DATALAB'
  AND mapping.dataset = 'visitor'
  AND mapping.status = 'ACTIVE'
  AND region.active
  AND source.valid_from <= CURRENT_DATE
  AND (source.valid_to IS NULL OR source.valid_to >= CURRENT_DATE)
ORDER BY region.code;
```

수집 응답에서 공식 code와 mapping이 맞지 않거나 검증 근거가 만료·변경된 경우 등록하지 않는다. DataLab snapshot publish를 실패시켜 이전 active revision을 유지하는 것이 잘못된 지역 관측값을 배포하는 것보다 안전하다.

## Staging smoke 자동화

`.github/workflows/datalab-staging-smoke.yml`은 `Staging Deploy` 성공 직후 자동 실행되며
필요하면 `workflow_dispatch`로 다시 실행할 수 있다. workflow는 다음을 한 번에 검증한다.

1. 보호된 operations endpoint로 실제 DataLab 수집을 실행한다.
2. read-only DB 연결로 `ACTIVE` registry의 provenance, active revision, `COMPLETE` 관측을 확인한다.
3. 배포 image에 bake된 Git SHA가 `Staging Deploy`의 SHA와 같은지 확인한다.
4. DB에서 선택한 지역·기준일·방문자 수와 Insights 및 VisitReview projection 값이 정확히 같은지 확인한다.
5. 같은 SHA의 PostgreSQL 통합 테스트로 실패 batch가 기존 active revision을 보존함을 확인한다.
6. token, service key, DB URL, provider payload, 지역 코드를 제거한 JSON 증적을 30일 artifact로 보관한다.

필수 staging 설정은 `STAGING_SPRING_URL` variable과
`ONMARU_DATALAB_OPERATIONS_TOKEN`, `ONMARU_DATALAB_VISITOR_SERVICE_KEY`,
`ONMARU_STAGING_READONLY_DB_URL` secrets다. 실행 전에 배포 runtime의
`ONMARU_SECRET_DATALAB_OPERATIONS_TOKEN_CURRENT`가 workflow token과 같은지 확인한다.

성공한 실행만 검증 링크와 함께 Issue #392를 닫는다. 배포 SHA 불일치, 공개 projection
불일치, 빈 VisitReview 검증 표본도 실패로 처리한다. 실패하면 #392에 실행 링크를 남기고
열린 상태를 유지한다. 실패 batch의 active revision 보존은 같은 workflow의 PostgreSQL 통합 테스트
`JdbcDataLabVisitorSnapshotPublisherTests.keepsThePreviousActiveRevisionWhenStagingFails`가
보장하며, 운영 복구는 registry/provenance 또는 provider 설정을 고친 뒤 smoke를 재실행한다.

로컬에서는 secret 없이 순수 검증 로직만 실행한다.

```bash
node --test scripts/test/datalab-staging-smoke.test.mjs
```
