# TourAPI 상세 수집 정책

- 상태: 시행 준비 (이슈 #737, PR #738)
- 기준일: 2026-10-14
- 이 문서가 수집 범위와 주기의 기준입니다. 정책을 바꾸면 이 문서와 아래 "변경 위치"를 함께 고치고, 변경 이력에 기록합니다.

## 1. 목적

TourAPI 상세 본문(`detailCommon2`)을 운영 DB에 적재해서, 지도 상세와 이후 임베딩(RAG)의 입력으로 씁니다. 대상은 OnMaru의 메인 컨셉(한옥, 궁궐, 전통시장)에 맞춘 장소로 한정합니다.

## 2. 수집 대상과 우선순위

| 순위 | 대상 | 판정 기준 | 2026-10-12 활성 건수 |
|---|---|---|---|
| 1 | 한옥 | `category = HANOK` | 207 |
| 2 | 한옥숙박 | `category = HANOK_STAY` | 498 |
| 3 | 한옥카페 | `category = HANOK_CAFE` | 1,769 |
| 4 | 한옥체험 | `category = HANOK_EXPERIENCE` | 346 |
| 5 | 궁궐 | `category = HISTORIC_SITE` 이면서 `lcls_systm3 = HS010100` | 73 |
| 6 | 전통시장 | `category = TRADITIONAL_MARKET` | 1,105 |
| 제외 | 일반 역사유적 (`HISTORIC_SITE`의 나머지) | - | 약 3,270 |
| 제외 | 전통음식·맛집 (`TRADITIONAL_FOOD`) | - | 8,148 |

- 합계 약 3,998곳입니다.
- 장소는 활성 `kto-korean-tour` revision의 `ACTIVE` 상태만 대상입니다.
- 같은 `contentid`가 여러 행으로 있으면 우선순위가 높은 한 건만 씁니다.

## 3. 호출 상한과 주기

- **일일 상한:** 상세 기능(`detailCommon2`)당 **800건**. 기능별 일일 한도 1,000건에서 사이트 실시간 조회 몫 200건을 남깁니다. 설정 상한은 800을 넘을 수 없습니다.
- **초기 적재:** 매일 04:30 KST에 실행합니다. 대상 약 4천 건을 800건/일로 약 5일에 끝냅니다.
- **증분 주기:** 초기 적재가 끝나면 목록 동기화와 같은 **14일 주기**로 바뀝니다. 이때 증분은 변경된 장소 수십 건 수준으로 예상합니다(2026-10-08 → 10-12 측정에서 대상 카테고리 변경 13건, 14일 환산 약 45건).
- **목록 동기화 주기:** 기본 14일입니다(`onmaru.tourapi.sync.minimum-interval` 미설정 시). 값이 있으면 설정이 우선합니다.

## 4. 변경 감지 규칙 (증분)

- 1차: 목록의 `modifiedtime`이 저장값과 다르면 변경 후보로 봅니다.
- 2차: 후보에 대해 목록 `raw_hash`를 비교해 실제 내용 변화를 확인합니다. 해시가 같으면 시각만 바뀐 것으로 보고 상세를 다시 호출하지 않습니다.
- `modifiedtime`이 없으면 바로 해시로 비교합니다.
- 이 규칙은 2026-10-08 → 10-12 측정(해시 변화 396건 모두 `modifiedtime` 변화 있음, 시각만 바뀐 19건)을 근거로 합니다. 설계 문서(2026-10-04)는 해시 기준이었으므로, 이 규칙을 적용하려면 `handoff.md`의 결정 기록도 함께 바꿔야 합니다.
- 상세 본문만 바뀐 경우는 목록 필드에 없어서 감지되지 않습니다. 이를 보완하려고 상세는 30·60·180일 TTL로 순환 재검증합니다(설계 기준, 호출 상한 안에서).

## 5. 실패와 중단

- `RATE_LIMITED`, `AUTH_OR_PERMISSION_ERROR`, `UPSTREAM_TIMEOUT_OR_GATEWAY_ERROR`, 전송 오류: 그날 남은 호출을 멈추고 다음 실행에서 이어 갑니다.
- `PROVIDER_PARAMETER_ERROR`, `SCHEMA_DRIFT`, 응답 건수 이상: 해당 장소만 `FAILED`로 기록하고 계속합니다. 실패 건은 미시도 건 뒤에 다시 시도합니다.
- 동시 실행(블루/그린 배포 중 두 인스턴스)은 advisory lock으로 막습니다.

## 6. 키와 로그

- 수집용 키는 사이트 실시간 조회 키와 분리합니다. 환경변수: `ONMARU_SECRET_TOURAPI_DETAIL_COLLECTION_KEY_CURRENT`. 키가 없으면 작업이 만들어지지 않습니다.
- 로그에는 건수와 사유 코드만 남깁니다. 본문과 키는 남기지 않습니다.

## 7. 미정 사항

- **사이트 프록시 상세 호출:** `/api/tour/{operation}`이 상세 operation을 제한 없이 중계합니다. 호출 제한을 걸지, 상세를 DB 값으로 바꿀지 결정이 필요합니다. 이 결정 전에는 릴리스하지 않습니다.
- **궁궐 목록 확인:** `HS010100` 73곳에 관아나 동헌이 섞였는지 이름을 훑어 확인합니다.

## 8. 변경 위치 (정책을 바꿀 때 고칠 곳)

| 바꾸는 것 | 위치 |
|---|---|
| 일일 상한 | `DetailCollectionPolicy.MAX_DAILY_CAP` |
| 우선순위와 대상 판정 | `JdbcDetailCollectionStore.nextTargets`의 `CASE WHEN` 블록 |
| 궁궐 코드 | `DetailCollectionPolicy.PALACE_LCLS_SYSTM3` |
| 실행 시각 | `DetailCollectionScheduler`의 cron (`onmaru.tourapi.detail-collection.cron`) |
| 목록 동기화 주기 | `TourApiClientConfiguration` 기본값 또는 `ONMARU_TOURAPI_SYNC_MINIMUM_INTERVAL` |

정책 변경은 이슈를 만들고, 이 문서와 코드를 같은 PR에서 함께 바꿉니다.

## 9. 변경 이력

| 날짜 | 변경 | 근거 |
|---|---|---|
| 2026-10-14 | 최초 정책: 6개 대상, 궁궐은 `HS010100`, 일일 상한 800 | 이슈 #737, PR #738 |
