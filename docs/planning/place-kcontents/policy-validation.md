# #681 후보 정책 실데이터 검증과 v1 동결

## 측정 범위와 재현 방법

- 관측 시각: TourAPI 2026-10-08 13:39:17 UTC, 운영 PostgreSQL의 `kto-korean-tour` active revision `a880003c-04d1-4209-8cf5-1d0c80b8875e`(2026-10-04 18:01 UTC 게시), 기존 공개 API 2026-10-08 13:41 UTC.
- 원천: 운영 앱과 같은 TourAPI 키로 국문 `areaBasedList2` 34개 소분류, `searchKeyword2` 4개 키워드를 실제 호출했다. `numOfRows=1000`; 총 41페이지를 받아 매 응답의 `resultCode`, `totalCount`, 수신 행 수를 검증했다. 요청당 평균 quota 사용량은 1회로 보되 공급자의 실제 quota 차감 정책은 별도 확인 대상이다.
- DB: 운영 PostgreSQL의 별도 readonly login으로 active revision만 SELECT했다. 원천 49,613행, 공개 `ACTIVE` 23,637행, `catalog_screen_hanok_placements` 0행이었다. 운영 데이터 수정은 없었다.
- 재현: `scripts/policy-validation/capture-tourapi.py`를 키가 설정된 격리 환경에서 실행하고, `readonly-catalog-snapshot.sql`을 readonly login의 `psql -XAt`로 실행해 JSONL을 만든다. 두 원본 파일을 Git 외부에 보관한 채 `python3 scripts/policy-validation/dry-run-policy.py <tourapi.json> <db.jsonl>`을 실행한다. 이번 원본 SHA-256은 TourAPI `8f679b3a26bfa7d423a4cfce278cdca1b601d91c163b1905eddccb4d59cff16e`, DB JSONL `3093722ba85722bde6f1cf6c10804f64035dbecbe8888ac3bc505354fc3b6ed7`이다. 원본은 키/개인정보 없이도 운영 장소 전체의 복제이므로 저장소에 넣지 않았다.
- 공개 API 기준선은 [`legacy-api-2026-10-08.json`](../../contracts/fixtures/discovery-baseline/legacy-api-2026-10-08.json)에 고정했다. `scripts/policy-validation/capture-legacy.py`로 재측정할 수 있다. 동적 `countsAsOf`와 요청 ID는 비교 대상에서 뺐다.

## 34개 소분류의 실제 페이지와 ID

아래 SHA는 각 소분류의 `contentId`를 사전순 정렬해 줄바꿈으로 연결한 값이다. 총량은 2026-10-07 PRD 관측치 9,800보다 1건 많다(`FD050200`: 49→50). `totalCount` 합 9,801과 실제 수신 행 9,801, 고유 ID 9,801이 일치하고 소분류 간 중복은 없었다.

| 소분류 | total | 받은 page | 받은 행 / 고유 ID | 기존 공개 교집합 | ID SHA-256 앞 12자리 |
|---|---:|---:|---:|---:|---|
| `HS010100` | 73 | 1 | 73 / 73 | 73 | `256f8c177d63` |
| `HS010200` | 185 | 1 | 185 / 185 | 185 | `8f6097b8b307` |
| `HS010300` | 36 | 1 | 36 / 36 | 36 | `022976422180` |
| `HS010400` | 208 | 1 | 208 / 208 | 208 | `0a5bd155d39b` |
| `HS010500` | 78 | 1 | 78 / 78 | 78 | `0b66fb6e234e` |
| `HS010600` | 111 | 1 | 111 / 111 | 111 | `b2b83d44ff8f` |
| `EX010100` | 63 | 1 | 63 / 63 | 62 | `f75fa6fc9c5a` |
| `AC030200` | 498 | 1 | 498 / 498 | 498 | `cc32dffe0f10` |
| `VE040100` | 311 | 1 | 311 / 311 | 0 | `9c87219f0309` |
| `VE040200` | 256 | 1 | 256 / 256 | 130 | `fe100a770e2d` |
| `HS010700` | 52 | 1 | 52 / 52 | 52 | `4677669b7bc0` |
| `HS011100` | 65 | 1 | 65 / 65 | 65 | `527be7710525` |
| `HS011200` | 393 | 1 | 393 / 393 | 392 | `16f42134c420` |
| `EX040200` | 3 | 1 | 3 / 3 | 3 | `997c4f3ef9f8` |
| `HS020100` | 250 | 1 | 250 / 250 | 250 | `1b0a61d2539b` |
| `HS020300` | 117 | 1 | 117 / 117 | 117 | `395d58e208ef` |
| `HS030100` | 1005 | 2 | 1005 / 1005 | 663 | `70a25c2af25d` |
| `VE070100` | 596 | 1 | 596 / 596 | 595 | `78bf9e51fc77` |
| `VE070200` | 157 | 1 | 157 / 157 | 157 | `3a61b0f73e01` |
| `VE070300` | 528 | 1 | 528 / 528 | 528 | `14d8e82d46a8` |
| `VE090100` | 176 | 1 | 176 / 176 | 0 | `d7e157770bed` |
| `VE090400` | 41 | 1 | 41 / 41 | 0 | `9c4e481b1175` |
| `FD040400` | 2 | 1 | 2 / 2 | 2 | `a976a431f674` |
| `FD050200` | 50 | 1 | 50 / 50 | 15 | `d867c9204be1` |
| `SH050100` | 52 | 1 | 52 / 52 | 52 | `be6a8ea6a7eb` |
| `SH060100` | 252 | 1 | 252 / 252 | 252 | `df8c8188e7f3` |
| `SH060200` | 576 | 1 | 576 / 576 | 576 | `5c1a32eed39c` |
| `NA040700` | 209 | 1 | 209 / 209 | 209 | `e0a689c5f673` |
| `VE010100` | 20 | 1 | 20 / 20 | 0 | `ee4374ffcc33` |
| `VE010900` | 7 | 1 | 7 / 7 | 0 | `22b1380b3bc0` |
| `EX060100` | 2 | 1 | 2 / 2 | 0 | `cf77c663f4b5` |
| `EX060300` | 2 | 1 | 2 / 2 | 0 | `1b130837a3da` |
| `FD050100` | 2983 | 3 | 2983 / 2983 | 1707 | `bb78f6853427` |
| `EX030100` | 444 | 1 | 444 / 444 | 444 | `74b42e1e31f3` |

## 구제 검색과 기존 게시본 차이

| 검색어 | total / 고유 ID | 선택 34개 밖 ID |
|---|---:|---:|
| 한옥 | 209 / 209 | 48 |
| 고택 | 109 / 109 | 5 |
| 궁궐 | 1 / 1 | 1 |
| 전통마을 | 4 / 4 | 3 |

검색 4개의 단순 합은 323건, `contentId` 합집합은 321건이다. 중복 제거 뒤 선택 34개 밖 ID는 **57건**, 전체 후보 합집합은 **9,858건**이다. `궁궐` 검색의 유일 결과 `1953123`은 장소가 아닌 여행 코스(`C01150001`)라 구제 검색을 자동 공개로 연결하면 안 된다. 예외 중 `2700971` 락고재 서울 북촌 한옥호텔은 숙박 분류 `AC010100`이며, `4013381` 장흥한옥수영장은 레포츠 `LS020700`이므로 제목의 “한옥”만으로 같은 결정을 내릴 수 없다.

운영 DB의 선택 34개 원천은 9,804건이고 이번 실시간 결과와 교집합은 9,799건이다. 실시간에만 2건, DB에만 5건이 있다. 기존 공개 23,637건 중 이번 후보 합집합과 교집합은 7,475건, 후보 밖은 16,162건이다. 후보 밖을 삭제하거나 기존 공개 API에서 숨기는 작업은 이번 Wave에 없다.

## 동결한 `discovery-candidate-v1.0.0` 판정

이 버전은 **새 탐색용 후보 판정**이다. `INCLUDE`도 세부 소개·장소 일치·이미지 권리 등 게시 quality gate를 통과하기 전에는 공개되지 않는다. 분류 코드와 제목만으로 촬영 관계 또는 `HANOK`을 만들지 않는다. W2는 이 정책 버전과 reason code를 저장하고 세부 증거·사람 override를 반영한다.

1. `contentId`, 제목 또는 대한민국 좌표 범위가 유효하지 않으면 `EXCLUDE/SOURCE_INVALID`. 드론·케이블카·카지노·골프·워터파크·키즈카페·복합쇼핑몰의 명백한 비관련 제목은 `EXCLUDE/UNRELATED_FACILITY`.
2. A그룹 10개 소분류는 유효성 검사를 통과하면 `INCLUDE/STRONG_TAXONOMY_PENDING_DETAIL_GATE`. `HS`와 한옥 숙박은 `CORE_TRADITIONAL_PLACE`, `EX010100`/`VE04`는 `TRADITIONAL_EXPERIENCE` 후보 역할이다. 이는 자동 게시 승인이 아니다.
3. B그룹 22개 소분류는 제목의 명확한 전통문화 신호가 있으면 `INCLUDE/CONDITIONAL_TITLE_SIGNAL_PENDING_DETAIL_GATE`; 없으면 `REVIEW/CONDITIONAL_NEEDS_CONTEXT`. 종교 시설·일반 박물관·시장 등에서 코드 자체로 포함을 확정하지 않는다.
4. 넓은 카페 `FD050100`과 체험마을 `EX030100`은 제목 신호가 있어도 `REVIEW/BROAD_CLASS_TITLE_SIGNAL_NEEDS_EVIDENCE`로 두고, 없으면 `EXCLUDE/BROAD_CLASS_NO_TRADITION_SIGNAL`. 한옥 카페·마을은 상세/공식 근거 확인 후 승격한다.
5. 선택 밖 검색 결과는 `EV`, `LS`, `C01`처럼 행사·레포츠·코스이면 장소 근거 없음으로 제외한다. 나머지는 제목 신호가 있어도 `REVIEW/KEYWORD_RESCUE_NEEDS_EVIDENCE`이며, 없으면 `EXCLUDE/RESCUE_NO_PLACE_EVIDENCE`다. 제목만으로 `INCLUDE`하지 않는다.

이번 title-level dry-run은 `INCLUDE` 1,983, `REVIEW` 4,463, `EXCLUDE` 3,412건이다. 기존 공개와 교집합은 각각 1,532 / 3,809 / 2,134건이며, 기존 공개 밖의 새 `INCLUDE` 후보는 451건이다. 이는 **새 공개 예상 건수나 검증 완료 장소 수가 아니다**. 세부 데이터와 수동 검수는 W2에서 적용한다. 각 판정 집합의 SHA-256은 `INCLUDE` `e10e26b4c922b18a34e901fafb1ea8e92bdf778c9908a5347de54b066076c68e`, `REVIEW` `7d56521b787c49be9522302ccf347d0282ca778225b4b5da0dc1ead8f06dce59`, `EXCLUDE` `11dc39e3ffe7761ba94bff774eaea0ee4885fc75c33845cb313ba150fed2e256`이다.

대표 판정은 `126508` 경복궁(`HS010100`) `INCLUDE/CORE_TRADITIONAL_PLACE`이고 `HANOK`이 아니다. `1604652` 경복궁 건청궁(`HS011200`)은 상세 확인 전 `REVIEW`; `2850913` 가는곶 세화(`FD050100`)는 전통 신호가 없는 넓은 카페라 `EXCLUDE`; `2700971` 락고재 서울 북촌 한옥호텔(`AC010100`)은 구제 검색 `REVIEW`다. `1953123` 한양도성 안 궁궐과 학교이야기(`C01150001`)는 여행 코스라 `EXCLUDE`다. `1911506` 세계골프역사박물관은 명백한 비관련 시설로 `EXCLUDE`다. 분류별 전체 dry-run 출력은 동일 스크립트로 재생한다.

## 기존 계약과 #569 회귀 기준

운영 API에서 `GET /api/v1/places/p-tourapi-126508`는 200이고 기존 category `HISTORIC_SITE`를 유지한다. 서울 중심 1.5km `GET /api/v1/map/places`의 첫 페이지에 이 ID가 포함됐다. 따라서 #569의 FE 지도 누락은 이번 관측에서 BE 장소 부재로 재현되지 않았다. FE가 실제 사용하는 bbox·limit·fallback 경로는 별도로 확인해야 하며 #569는 닫지 않는다. 기존 한옥 목록은 1,096건, screen-hanok 관계는 0건이었다. 신규 탐색 revision이 기존 `kto-korean-tour` active pointer와 이 API들의 응답 집합을 바꾸면 통합 gate 실패다.

## modifiedtime/hash 관측과 남은 검증

후보 합집합 9,858건 중 운영 snapshot에 있던 9,856건에서 `modifiedtime` 동일 9,711건, 변경 145건이었다. 제목·주소·소분류·좌표를 정상화해 비교한 82건의 변경은 모두 `modifiedtime` 변경과 함께 나타났다. 예를 들어 `128526` 경주 동궁과 월지는 좌표와 수정시각이 함께 달라졌다. **수정시각 동일·내용 변경 0건은 이 한 시점의 선택 필드에서만 관측한 값**이다. 상세·이미지 원문과 기존 `raw_hash`의 전체 canonical payload를 재계산한 결과가 아니다. 따라서 hash 생략의 근거가 되지 않으며 W3은 SHA-256 최종 diff를 유지해야 한다. 원천 `raw_hash` 49,613건은 모두 64자리였다.

## ADR-0010 계승 경계

ADR-0010의 기존 screen-hanok API 및 운영 관계 게시 정책은 이 작업으로 변경하지 않는다. 신규 K-Contents 관계는 별도 스키마와 신규 API에서만 근거 URL 자체가 아닌 **실제 작품의 해당 장소 촬영을 지지하는 근거**를 검증한다. 신규 탐색의 사람 override·검수 큐, 권리 확인, 버전형 판정과 legacy active 보존은 후속 ADR에서 명시적으로 계승/변경한다. 초안은 [`0016-discovery-candidate-publication.md`](../../decisions/drafts/0016-discovery-candidate-publication.md)이며 승인 전 ADR-0010의 accepted 상태를 바꾸지 않는다.
