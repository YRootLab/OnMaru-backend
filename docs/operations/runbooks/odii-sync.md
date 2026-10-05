# Odii scheduler 실행 이력과 AWS 운영 판정

Issue #382의 진단 절차다. Lightsail의 `/opt/onmaru` Compose 배포를 기준으로 하며, 이 문서는 실제 배포·원천 API 검증 완료 증거가 아니다. `production` 프로필의 JDBC store와 V038 migration 적용이 선행되어야 한다. `staging` 프로필에서는 scheduler가 비활성화되므로 자동 동기화를 기대하지 않는다. 현재 배포/접근 권한은 [Lightsail Compose 설명](../../../infra/lightsail/README.md)을 따른다.

## 실행과 로그 연결

배포 준비 이벤트는 `application-ready`, KST cron은 `scheduled-cron` trigger로 실행한다. 기본 cron은 `0 0 3 */3 * *`(KST 03:00, day-of-month `*/3`)이며 `onmaru.odii.sync.cron` 설정이 우선한다. 월 경계까지 엄밀한 72시간 주기는 아니다. 임의의 공개 수동 실행 API는 제공하지 않는다. 진단 목적으로 production 앱을 반복 재시작하거나 lease를 삭제하지 않는다.

승인된 SSH 세션에서 최근 로그의 scheduler 진단만 조회한다.

```sh
cd /opt/onmaru
docker compose --env-file .env logs --since=24h --no-log-prefix spring-api \
  | rg 'ODII_SYNC_(STARTED|COMPONENTS|PHASE|TERMINAL|HISTORY_FAILED)'
```

같은 `runId`로 STARTED와 terminal 한 개를 연결한다. `ODII_SYNC_TERMINAL status=COMPLETED`가 성공 로그이며, `status=FAILED`와 `status=SKIPPED`는 성공이 아니다. terminal에는 trigger, dataset, revisionId, leaseGeneration, fetched/mapped/staged/published/tombstones와 내부 reason/phase만 들어간다.

| reason / phase | 확인할 경계 |
| --- | --- |
| `MISSING_COMPONENT` / `DEPENDENCY_CHECK` | sync service, revision store, DataSource 존재 여부 boolean. production 설정 및 필수 credential 설정 여부만 확인 |
| `COMPONENT_CREATION_FAILED` / `DEPENDENCY_CHECK` | 필수 bean 생성 실패. 안전한 exceptionType과 배포 설정 확인 |
| `LEASE_NOT_ACQUIRED` / `LEASE` | 다른 실행의 유효 lease. 정상 경쟁 skip일 수 있으며 임의 해제 금지 |
| `LEASE_DB_ERROR` / `LEASE` | lease SQL/DB 권한·연결 오류. 경쟁 skip과 구분 |
| `SOURCE_FAILED` / `FETCH` | provider timeout/인증/응답 오류. provider 오류 본문이나 요청 URI를 복사하지 않음 |
| `MAPPING_FAILED` / `MAP` | 허용 언어·정규화·필드 유효성 |
| `STAGE_OPEN_FAILED`, `STAGING_FAILED`, `STAGE_COMPLETION_FAILED` / `STAGE` | migration, runtime 권한, stage transaction |
| `PUBLISH_FAILED` 또는 publication status / `PUBLISH` | lease fencing, active revision CAS, publication transaction |

`ODII_SYNC_HISTORY_FAILED`가 있으면 terminal 로그가 있어도 durable 기록 완료로 판정하지 않는다. DB 자체 장애나 DataSource 부재에는 이력을 저장할 수 없으므로 로그가 마지막 진단 경로다. 서비스 키, JDBC URL, Authorization, 환경변수 원문, `.env`, provider 오류 원문은 로그·문서·Issue 증거에 넣지 않는다. `docker compose config` 원문/`env` 출력도 수집하지 않는다.

## 읽기 전용 DB 조회

DB 포트를 인터넷에 공개하지 않고 Lightsail 내부 `postgres` 컨테이너에서 readonly login으로 접속한다. 아래 명령은 기존 컨테이너 환경의 credential을 사용하며 값은 출력하지 않는다. `set -x`를 켜지 않는다.

```sh
docker compose --env-file .env exec postgres sh -c \
  'PGPASSWORD="$ONMARU_DB_READONLY_PASSWORD" exec psql -X --set ON_ERROR_STOP=1 --host 127.0.0.1 --username "$ONMARU_DB_READONLY_USER" --dbname "$POSTGRES_DB"'
```

```sql
BEGIN READ ONLY;
SELECT id AS run_id, trigger_source, lifecycle_status, status,
       started_at, finished_at, failure_phase, error_code,
       revision_id, lease_generation,
       counts->>'fetched' AS fetched, counts->>'mapped' AS mapped,
       counts->>'staged' AS staged, counts->>'published' AS published,
       counts->>'tombstones' AS tombstones
FROM onmaru.operations_sync_runs
WHERE dataset = 'odii-audio'
ORDER BY started_at DESC LIMIT 20;

SELECT active.dataset, active.revision_id,
       count(story.story_id) FILTER (WHERE story.status = 'ACTIVE') AS active_stories
FROM onmaru.catalog_active_datasets active
LEFT JOIN onmaru.audio_story_versions story ON story.revision_id = active.revision_id
WHERE active.dataset = 'odii-audio'
GROUP BY active.dataset, active.revision_id;
COMMIT;
```

dataset을 변경했다면 두 SQL의 filter도 승인된 설정 값에 맞춘다. lifecycle `COMPLETED`는 기존 status enum의 `SUCCEEDED`와 대응한다. `SKIPPED`는 `ABANDONED`, `FAILED`는 `FAILED`이며 다른 sync job의 기존 enum 사용은 바뀌지 않는다.

fetched는 정상 수신한 page의 원천 story 수(중복·curation 제외 포함), mapped는 curation·중복 제거 후 성공적으로 변환한 story 수다. staged/published는 story 수가 아닌 **spot+story version 행 수**이며 이전 revision에서 복사한 행도 포함한다. published는 성공한 publication의 행 수이고 실패/skip이면 0이다. tombstones는 stage completion의 삭제 후보 spot+story 집계다. 실패 때 집계는 실패 지점까지 관측한 부분값이지 완전한 source snapshot이 아니다. 과거 run에는 fetched/mapped가 없을 수 있다.

## 공개 API 성공 판정

```sh
curl --fail --silent --show-error \
  'https://api.onmaru.site/api/v1/home/trending-sounds?language=ko-KR&limit=1' \
  | jq -e '.items | type == "array" and length > 0'
```

운영 성공은 다음 세 조건을 함께 만족해야 한다.

1. 해당 실행은 terminal 한 개가 `COMPLETED`이고 DB의 같은 run ID가 `SUCCEEDED/COMPLETED`, finished_at non-null이다. `HISTORY_FAILED`가 없어야 한다.
2. 실행 revisionId가 현재 active revision과 일치하고 ACTIVE stories가 1개 이상이다. 공개 조회 가능한 audio URL/언어/curation 조건도 만족해야 한다. 전체 수집이 같아 기존 revision을 재사용하는 경우에도 effective active revision으로 연결한다.
3. `trending-sounds`가 HTTP 200을 반환하고 items가 비어 있지 않다. health 200만으로 성공을 판정하지 않는다. active revision이 없거나 stories가 비어 있는 503/빈 200은 이번 초기 복구 목표를 충족하지 못한다.

증거에는 commit/image digest, 시각, run ID, 안전한 count/status, HTTP 상태만 남긴다. 실제 AWS 로그·readonly SQL·공개 API 확인은 운영 권한으로 별도 수행해야 하며 로컬 fixture GREEN을 그 증거로 대체하지 않는다.
