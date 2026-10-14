# TourAPI 상세 수집과 임베딩 준비 계획 (초안)

- 상태: 초안, 승인 전
- 기준일: 2026-10-14
- 범위: 한옥 계열, 전통시장, 궁궐·문화유산(세부 기준 미정). 맛집은 제외
- 운영 쓰기 없음. 이 문서는 계획만 담고, 수집 테이블·스케줄러·호출 로직은 아직 만들지 않는다.

## 1. 현재 동작 (코드와 운영 설정 기준)

| 항목 | 확인 내용 | 근거 |
|---|---|---|
| 일일 트리거 | 매일 03:00 KST | `TourApiCatalogSyncSchedulingAdapter`, `application.yaml`의 `ONMARU_TOURAPI_SYNC_CRON` 기본값 |
| 실제 실행 주기 | `minimumInterval` 미설정 시 코드 기본값 72시간. 운영 `.env`에 관련 값 없음 → 약 3일 주기 | `TourApiClientConfiguration` 95행, `isSyncDue` |
| 기본 호출 범위 | `lclsSystmCode2` + `areaBasedList2` 목록 페이지. 상세(`detailCommon2`) 호출 없음 | `TourApiCatalogSnapshotSource.streamFullSnapshot` |
| 페이지 크기 | `ONMARU_TOURAPI_PAGE_SIZE` 기본 1,000 → 전체 목록은 대략 50페이지 규모 (추정, 실측 필요) | `application.yaml` 57행 |
| 선택 소스 | 운영 `.env`에 `ONMARU_TOURAPI_SYNC_SELECTED_SOURCE` 없음 → 기본값 `false`. 아직 꺼져 있음 | `compose.yaml` 22행 |
| 주간 선택 discovery | 운영 `.env`에 `ONMARU_DISCOVERY_ENABLED` 없음. `ONMARU_DISCOVERY_API_ENABLED=true`는 별개 설정 | `SelectedDiscoverySchedulingAdapter` 26행 |
| 사이트 상세 조회 | 상세 열람 시 `detailCommon2`, `detailIntro2`, `detailInfo2` 실시간 호출. 한옥 상세가 DB에 있으면 생략 | `TourApiFallbackPlaceDetailStore` |

판단: 현재 운영의 TourAPI 갱신은 **전체 목록 기준이고 상세를 부르지 않는다.** 사용자님이 말씀하신 "신분류 + 6개 정도, 일 30건 안팎"은 **선택 소스 모드**의 규모입니다. 그 모드는 아직 켜져 있지 않습니다. 켜는 순간 후보마다 상세를 부르는 경로가 있는지는 별도로 확인해야 합니다.

## 2. 호출 장부 (기능별 일일 1,000건)

| 호출 주체 | 상세 기능 사용 | 상태 |
|---|---|---|
| 수집(이 계획) | `detailCommon2` 800건/일 상한 | 계획 |
| 사이트 실시간 조회 | `detailCommon2`, `detailIntro2`, `detailInfo2` | 호출량 실측 필요 |
| 일일 목록 갱신 | 상세 없음 | 운영 중 (목록 기능 쿼터 별도) |
| 주간 선택 discovery | `detailCommon2` 가능성 | 운영 설정상 꺼진 것으로 보임, 확인 필요 |
| 선택 소스 전환 후 일일 갱신 | 상세 호출 가능성 | 전환 전 확인 필요 |

## 3. 수집 일정 (800건/일)

| 날짜 | 누적 | 대상 |
|---|---|---|
| D1 | 800 | 한옥 계열 |
| D2 | 1,600 | 한옥 계열 |
| D3 | 2,400 | 한옥 계열 마무리 + 전통시장 일부 |
| D4 | 3,200 | 전통시장 + 궁궐·문화유산 일부 |
| D5 | 4,000 | 궁궐·문화유산 |
| D6 | - | 재시도와 보정 |

대상 수는 궁궐·문화유산 세부 기준이 정해지면 다시 계산한다.

## 4. 임베딩 전 조건

1. 본문 기준: `overview` 100자 이상 여부를 dry-run으로 확인한 뒤 확정 (현재 운영 `overview` 100자 이상은 0곳)
2. 권리: `cpyrht_div_cd`와 이용 조건 확인 후 임베딩 승인
3. 수집 결과는 공개 revision과 분리된 별도 테이블에 저장 (스키마는 승인 후 설계)

## 5. 결정 필요

- 수집용 키와 사이트용 키를 분리할지
- 선택 소스 전환 시점과, 전환 시 일일 갱신이 상세를 부르지 않도록 할지
- 주간 discovery를 끈 상태로 둘지
- 궁궐·문화유산 세부 기준
- 맛집 제외 확정

## 6. 읽기 전용 확인 명령 (운영, 값 마스킹)

```sh
sudo grep -hE '^(ONMARU_TOURAPI|ONMARU_DISCOVERY)' /opt/onmaru/repo/infra/lightsail/.env
```

키·비밀번호·URL 값은 출력하지 않는다.
