# 신규 탐색 파일럿과 스테이징 되돌리기

이 절차는 #691의 **스테이징 전용 실행 기록**이다. `testing/kcontents-pilot/`의 예시는 합성 입력이며 100건 또는 1000건 실측 결과가 아니다. 실제 검색 제공자, 허용 도메인, 추출 CLI, 스테이징 관리자 JWT·worker token·읽기 전용 DB 계정이 준비되기 전에는 단계 승격을 기록하지 않는다. 운영 DB와 기존 catalog active pointer는 이 절차에서 변경하지 않는다.

## 배포 전 준비

1. PR의 `verify`가 통과한 뒤 `develop`에 병합한다. [스테이징 배포 절차](../../../infra/lightsail/staging/README.md)의 `deploy_staging=true`는 **동일 develop SHA의 verify**를 요구하며 실행 중인 스테이징을 먼저 멈춰야 한다. 이 PR 단계에서 원격 실행 결과를 주장하지 않는다.
2. 운영자는 staging `.env`에서 `ONMARU_DISCOVERY_API_ENABLED=false`, `ONMARU_KCONTENTS_RESEARCH_ENABLED=false`를 확인하고 `docker compose --project-directory infra/lightsail/staging --env-file infra/lightsail/staging/.env -f infra/lightsail/staging/compose.yaml config --quiet`로 렌더링한다. worker token은 staging 전용 값으로만 설정한다. 둘 다 기본값은 false다. 관리 SSH에서 `sudo /opt/onmaru/staging-repo/infra/lightsail/staging/set-kcontents-flags.sh on on`으로 staging 기동 후 두 flag를 켠다. 이 root 전용 스크립트는 staging DB명·`.env` 권한·Spring health를 검사하고 staging Spring만 재생성한다. FE 제한 operator와 CI deployer에는 이 명령 권한이 없다.
3. TourAPI staging key, 실제 검색 제공자 endpoint/key와 허용 host, 근거 host/source tier, CLI argv·모델 버전, 호출 단가/총 예산, staging 관리자 JWT, worker ID/token, SELECT 전용 DB 계정의 소유자와 만료 시각을 실행 기록에 적는다. 비밀 값은 보고서·Git에 넣지 않는다. `ONMARU_SEARCH_PROVIDER=http-json`, `ONMARU_SEARCH_LIVE_ENABLED=true`, `ONMARU_EXTRACTOR_ENABLED=true`가 아닌 실행은 실측으로 인정하지 않는다.
4. 후보 장소는 서로 다른 내부 UUID 100개, 다음 단계는 서로 다른 UUID 1000개를 사전 검토한다. 7개 기존 FE 카드도 별도 재검증 목록으로 기록한다. 관계나 작품 수를 미리 확정하지 않는다. 신규 선택 dataset만 게시하며 기존 active catalog pointer는 건드리지 않는다.

## 기준선과 단계별 측정

다음 명령은 repo root에서 실행한다. `PILOT_LEGACY_PLACE_ID`에는 staging seed의 안정적인 상세 ID를 넣고, 저장 목록을 검사할 staging 전용 세션 cookie를 설정한다. 출력에는 공개 응답이 들어갈 수 있으므로 접근 제한된 작업 디렉터리에 둔다. 인증 응답은 hash만 보관한다.

```sh
export PILOT_LEGACY_PLACE_ID=p-staging-hanok-a
export ONMARU_STAGING_SESSION_COOKIE='...'
python3 testing/kcontents-pilot/legacy_golden.py capture --base-url https://staging-api.onmaru.site --manifest testing/kcontents-pilot/legacy-requests.example.json --output pilot-private/legacy-before.json
```

100건의 reason은 `PILOT_691_100_YYYYMMDD`, 1000건은 `PILOT_691_1000_YYYYMMDD`처럼 고유하게 잡는다. 관리자 JWT로 [작업 API 계약](../../contracts/kcontents-research-worker-api.md)의 `POST /api/v1/internal/kcontents/research/admin/jobs`에 검토한 장소 UUID, 입력 JSON, source fingerprint와 reason을 등록한다. `enqueue.py`는 100/1000개 고유 장소와 JSON을 먼저 검증하고 `--execute`에서만 staging에 등록한다. 전용 JSONL의 각 행은 `placeId`, `sourceFingerprint`, 문자열 `inputJson`을 포함한다. 응답 job ID와 중복/실패를 보관하고 정확히 100/1000개인지 확인한다. 각 worker 실행의 `--max-jobs`는 최대 100이다. 여러 회차 실행 시 metrics JSONL을 한 파일에 누적하고 **하루 전체 searchCalls·estimatedCost**를 매 회차 더해 승인 예산 전에 중단한다. worker는 job·근거·추출을 같은 lease에서 최종 제출한다. fixture 옵션을 실측 실행에 사용하지 않는다.

```sh
python3 testing/kcontents-pilot/enqueue.py --input pilot-private/places-100.jsonl --stage 100 --reason PILOT_691_100_YYYYMMDD
ONMARU_STAGING_ADMIN_JWT='...' python3 testing/kcontents-pilot/enqueue.py --input pilot-private/places-100.jsonl --stage 100 --reason PILOT_691_100_YYYYMMDD --execute --output pilot-private/jobs-100.jsonl
python3 -m workers.kcontents.search.worker --max-jobs 100 --metrics pilot-private/worker-100.jsonl
psql "$ONMARU_STAGING_READONLY_DB_URL" -X -q -A -t -v ON_ERROR_STOP=1 -v pilot_reason=PILOT_691_100_YYYYMMDD -f testing/kcontents-pilot/read-only-evidence.sql > pilot-private/db-100.json
python3 testing/kcontents-pilot/report.py --manifest pilot-private/manifest-100.json --database pilot-private/db-100.json --worker-metrics pilot-private/worker-100.jsonl --judgments pilot-private/judgments-100.jsonl --output pilot-private/report-100.json
```

`manifest`는 `cohort.example.json` 구조를 따른다. 실제 staging/http-json임을 **운영자가 제공자 로그와 worker 설정으로 대조한 뒤** 기입한다. SQL은 SELECT 전용 계정으로 실행한다. 모든 공개 관계 ID의 원문 URL·인용·작품·장소·지역·촬영 맥락을 사람이 판정해 `judgments` JSONL에 relationId, VALID/INVALID, reviewer, reviewedAt을 적는다. `READY_FOR_OPERATOR_REVIEW`는 통계 검토 시작 신호일 뿐 공개 승인이나 파일럿 완료를 뜻하지 않는다. URL과 장소가 같은 기존 관계가 SQL 집계에 포함될 수 있으므로 게시 전후 관계 ID diff와 validation audit로 이번 단계의 기여를 별도 확인한다. 지역·작품 다양성, 최소 50개 **검증된 고유 장소** 목표를 실제 값으로 기록한다. 100건 결과와 장애 리허설이 통과해야 1000건을 같은 방식으로 시작한다.

## 공개·회귀·장애 리허설

선택 revision 승인 및 게시에는 기존 관리자 승인 경계를 사용한다. 게시 전후 다음을 기록한다: active selected revision ID, legacy catalog active ID, 신규 API의 topics/places/works 응답, retained revision 커서의 다음 페이지, 현재 revision cursor의 다음 페이지. 승인 철회 뒤 과거 커서에서 해당 항목이 숨겨지는지 확인한다. 별도의 legacy golden은 홈·한옥·지도·기존 상세·인증된 찜·Odii·screen-hanok의 같은 경로와 status/body hash를 대조한다.

```sh
python3 testing/kcontents-pilot/legacy_golden.py capture --base-url https://staging-api.onmaru.site --manifest testing/kcontents-pilot/legacy-requests.example.json --output pilot-private/legacy-after.json
python3 testing/kcontents-pilot/legacy_golden.py compare --before pilot-private/legacy-before.json --after pilot-private/legacy-after.json
```

staging에서만 각각 TourAPI key 거부/timeout, 검색 제공자 429/timeout, worker 중단·lease 만료를 주입한다. 실패 작업의 재시도·격리, 기존 공개 자료의 지속, flag off 뒤 신규 route의 비노출, legacy golden 동일성을 기록한다. 장애를 되돌리고 재큐잉은 관리자 감사 기록과 새 epoch를 확인한다. 2시간 자동 중지 전에 증거를 수집한다. 운영 API나 운영 key로 장애를 주입하지 않는다.

## 중단과 되돌리기

품질/비용/권리 검증이 실패하면 관리 SSH에서 `sudo /opt/onmaru/staging-repo/infra/lightsail/staging/set-kcontents-flags.sh off off`를 실행한다. 새 공개 route가 사라지고 legacy golden이 유지되는지 확인한다. 선택 revision만 이전 정상 게시본으로 되돌려야 한다면 **현재 active UUID와 대상 PUBLISHED UUID를 직접 확인한 뒤** 아래 staging 전용 CAS wrapper를 실행한다. 이 스크립트는 `.env`의 `onmaru_staging` DB와 staging Compose PostgreSQL만 사용한다. 기존 catalog pointer나 장소·근거 행을 삭제하지 않는다. UUID가 바뀐 경쟁 상황에서는 실패해야 한다.

```sh
sudo /opt/onmaru/staging-repo/infra/lightsail/staging/rollback-discovery.sh '<현재 revision UUID>' '<이전 정상 PUBLISHED UUID>'
```

되돌린 뒤 신규 목록·작품/관계 API, retained cursor, legacy golden을 다시 대조한다. staging 이미지 rollback은 [기존 배포 절차](../../../infra/lightsail/staging/README.md)의 previous image 기록과 migration 호환성을 확인한 뒤 운영자가 수행한다. Flyway migration을 역실행하거나 과거 payload를 물리 삭제하지 않는다.

## FE 인계와 종료 조건

[FE 인계 템플릿](../../contracts/fixtures/kcontents-release/fe-handoff.md)에 실행 SHA, staging base URL, 신규 API 계약/fixture, 실제 revision·커서, 7개 카드 판정, 100/1000 보고서, golden diff, 장애·rollback 결과와 미해결 제약을 채워 FE #366에 전달한다. 이 PR의 템플릿 상태는 `BLOCKED`; 원격 staging 결과가 없는 동안 #691은 열린 상태로 둔다.
