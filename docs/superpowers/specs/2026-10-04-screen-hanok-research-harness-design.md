# 검색 중심 스크린 속 한옥 리서치 하네스 설계

## 목표

운영 `스크린 속 한옥`의 약 7개 항목을 재검증하고, 기존 catalog를 활용한 초기 backfill과 이후 증분 수집으로 고유 장소 100개 이상, 작품-장소 연결 150개 이상을 확보한다. FE가 드라마·영화·K-POP뿐 아니라 지역, 공개 연도, 기존 장소 category, 소리마루 연계 여부 등을 조합해 여러 섹션을 만들 수 있어야 한다.

검색 API가 먼저 근거를 수집하고 LLM은 수집된 근거의 구조화, 요약, 애매한 장소-작품 매칭 판정에만 사용한다. TourAPI 원본, 기존 장소 category, 사람이 확인했거나 출처로 검증된 작품 연결은 서로 다른 생명주기로 보존한다.

관련 작업은 GitHub Issue #603에서 추적한다.

## 관찰된 현재 상태

- `GET /api/v1/hanoks/screen-hanok`과 `K_DRAMA`, `CINEMA`, `KPOP` 필터가 구현되어 있다.
- 매일 03:00 KST에 이미 게시된 한옥 목록 전체를 20개씩 FastAPI에 전달한다.
- FastAPI는 Gemini Google Search grounding을 사용하고 grounding 결과에 실제로 포함된 URL만 통과시킨다.
- production의 `catalog_screen_hanok_placements`는 `(place_id, media_type, work_title)`을 기본 키로 사용한다.
- 한 번의 sync가 찾은 결과로 게시 테이블 전체를 교체하므로 검색 결과가 적거나 일부 후보를 놓치면 기존 게시 데이터가 줄어들 수 있다.
- 조사 이력, `NO_MATCH`, 재시도, 검색 cache, 복수 출처, 비용 및 다양성 지표가 지속되지 않는다.
- 현재 catalog revision schema와 qualification 단계에는 `raw_hash`, `normalized_hash`, `payload_hash` 및 SHA-256 계산 기반이 이미 있다. 새 변경 감지는 이 구조를 확장하며 중복 hash 체계를 만들지 않는다.

## 설계 원칙

1. 기존 장소 category는 변경하지 않는다. 스크린 속 한옥 정보는 장소에 연결된 작품 등장 사실이다.
2. 외부 검색이 evidence를 찾고 LLM은 evidence 안에서만 구조화한다.
3. TourAPI의 `createdtime`과 `modifiedtime`은 저장할 수 있지만 변경 감지의 신뢰 기준으로 사용하지 않는다.
4. 변경되지 않은 장소, 같은 검색 결과, 완료된 작품 연결을 반복 처리하지 않는다.
5. 새 조사 실패는 기존 정상 게시물을 삭제하지 않는다.
6. source observation, research claim, publication을 분리해 각 단계를 독립적으로 재실행할 수 있게 한다.
7. 검색 provider와 LLM provider는 port로 격리하며 Spring은 LLM SDK를 직접 호출하지 않는다.
8. 사람 검수 없는 자동 게시 결정인 ADR-0010을 유지하되, 기계 검증과 복수 evidence로 안전장치를 강화한다.

## 검토한 접근

### 전체 후보를 매번 LLM Search Grounding으로 조사

현재 방식과 가장 가깝지만 비용과 quota가 후보 수에 비례한다. 결과를 매번 교체하면 일시적인 검색 품질 저하가 운영 데이터 축소로 이어진다. 채택하지 않는다.

### 로컬 LLM이 전체 장소를 분류하고 작품을 추론

단순 텍스트 분류 비용은 줄일 수 있지만 최신 작품 발견, 실제 촬영 장소 확인, URL 근거 확보에 부적합하다. 출처 환각과 모델 운영 비용도 남는다. 향후 수집된 evidence의 저위험 분류 보조로만 검토한다.

### 검색 우선 증분 리서치 하네스

일반 코드가 후보와 변경을 선별하고, 검색 provider가 evidence를 수집하며, LLM은 evidence 구조화에만 사용한다. 조사 결과와 실패 상태를 누적하므로 비용을 제어하면서 데이터가 성장한다. 이 방식을 채택한다.

## 전체 흐름

```text
TourAPI 14일 주기 snapshot
  -> source record 정규화와 SHA-256
  -> active revision과 set diff
  -> 신규/변경 장소 research job 등록
  -> 기존 catalog 초기 backfill job 등록
  -> 검색 provider fan-out과 cache
  -> evidence 정규화·중복 제거·출처 등급화
  -> LLM evidence extractor
  -> 결정론적 claim validator
  -> 작품-장소-출처 누적 저장
  -> 다양성·품질 gate
  -> 공개 read model 갱신
```

## TourAPI 증분 변경 감지

### 안정 키와 hash

TourAPI `contentId`를 원천 레코드의 안정 키로 사용하고 기존 canonical place identity에 연결한다. `modifiedtime` 존재 여부나 정확성에는 의존하지 않는다.

원천 응답은 key 순서, 공백, null/빈 문자열, URL, 좌표 정밀도, 순서 의미가 없는 배열을 정규화한 canonical representation으로 변환한다. 요청 ID, 수집 시각, 페이지 번호와 기존 qualification 정책의 volatile field는 제외한다. SHA-256과 `hash_schema_version`을 함께 저장한다.

### set diff

수집한 전체 snapshot을 staging에 bulk load한 뒤 DB가 active revision과 `source identity + normalized hash`를 join한다.

- 새 identity: `ADDED`
- 같은 identity와 다른 hash: `CHANGED`
- 같은 identity와 같은 hash: `UNCHANGED`
- active에만 존재: `MISSING`

`MISSING`은 즉시 삭제하지 않고 연속 누락 횟수와 마지막 관측 revision을 기록한다. source record가 변경되더라도 기존 작품 연결은 보존하고 재조사 job만 추가한다. 전체 정렬된 identity/hash manifest의 dataset hash가 같으면 projection 재게시와 후속 job 생성을 생략할 수 있지만, ETag나 change feed가 없는 한 TourAPI pagination 자체는 수행한다.

### 기존 장소의 기본·상세 정보 갱신

목록 snapshot hash와 상세 정보 hash를 분리한다. 14일 통합 run은 전국 기본 목록을 받아 `list_hash`를 비교하고, 신규 장소와 `list_hash`가 바뀐 기존 장소만 상세 API 갱신 queue에 넣는다. 상세 응답은 `detail_hash`, `detail_checked_at`, `next_detail_refresh_at`을 저장한다. 이름, 주소, 좌표, category, 대표 이미지처럼 공개 read model에 영향을 주는 필드가 바뀌면 해당 장소 projection만 다시 게시한다.

목록 응답에 나타나지 않는 상세 필드 변경도 놓치지 않도록 변경 없는 장소를 중요도별 TTL로 순환 재검증한다.

- 운영 화면에 노출 중이거나 저장·조회가 많은 장소: 30일
- 스크린 속 한옥·소리마루 등 연결 콘텐츠가 있는 장소: 60일
- 그 밖의 활성 장소: 180일
- `MISSING`, 비공개, 장기 미사용 장소: 자동 상세 갱신 제외 또는 연 1회

TTL이 지나도 모든 상세를 한 번에 호출하지 않고 일일 quota 안에서 오래된 순서와 중요도 점수로 나눠 처리한다. 사용자가 오래된 장소 상세를 조회하면 현재 저장값을 즉시 반환하고, `next_detail_refresh_at`이 지났을 때 비동기 refresh job만 등록하는 stale-while-revalidate 방식을 사용한다. 동일 장소의 미완료 refresh job은 하나만 허용한다.

이미지 URL은 매번 파일을 다시 내려받지 않는다. URL이 바뀌었을 때만 새 metadata를 처리하고, 기존 대표 이미지는 낮은 빈도의 URL health check로 확인한다. 상세 API 실패, 빈 응답, 일시적인 이미지 장애는 기존 정상 데이터를 지우지 않으며 재시도 후에도 실패하면 `STALE`로 표시한다. 운영자는 특정 장소, category 또는 revision을 수동 재검증할 수 있다.

## 후보 선정

초기 backfill은 활성 canonical catalog 전체를 대상으로 단계적으로 수행한다. 기존 category를 변경하거나 새 category 체계를 도입하지 않는다.

우선순위 점수에는 기존 category, TourAPI 분류 코드, 이름과 소개의 전통문화 표현, 이미지 유무, 지역 다양성, 소리마루 연결, 기존 조사 상태를 사용한다. 고택, 한옥, 궁궐, 전통마을뿐 아니라 한옥 카페·숙소, 전통시장, 사찰, 서원, 향교, 공방, 전통 체험·음식·공연 장소를 포함한다. 1차 후보에서 목표 수량과 다양성을 확보하지 못하면 나머지 활성 장소로 조사 범위를 넓힌다.

정상 운영에서는 TourAPI 동기화와 스크린 속 한옥 증분 리서치를 14일 주기의 한 orchestration run으로 묶는다. 먼저 새 TourAPI snapshot을 수집·비교하고, `ADDED`, 작품 매칭에 영향을 줄 의미 있는 `CHANGED`, 재검증 기한이 지난 항목만 durable queue에 등록한 뒤 정해진 예산 안에서 처리한다. 축제·재난·실시간 혼잡처럼 짧은 freshness가 필요한 데이터가 아니므로 원천 수집, 검색, LLM 비용과 provider quota를 함께 절감한다. `UNCHANGED`와 아직 재검증 시점이 아닌 `NO_MATCH`는 제외한다.

초기 backfill은 이미 저장된 active catalog를 입력으로 사용하므로 새 TourAPI 수집을 기다리지 않는다. 100개 장소·150개 claim 목표를 달성할 때까지 매일 정해진 예산만큼 queue를 처리하고, 목표 달성 뒤 14일 통합 run으로 전환한다. evidence URL 생존 여부는 30일 주기로 가볍게 확인하고, 작품-장소 관계 전체 재조사는 180일 또는 source hash 변경 시 수행한다. 운영자가 긴급 원천 동기화나 재조사를 요청할 수 있는 수동 trigger는 제공하되 정상 주기를 우회하는 자동 trigger는 두지 않는다.

## durable research job

DB 기반 작업은 최소한 다음 정보를 지속한다.

```text
job_id, place_id, source_hash, reason
status, attempt_count, next_retry_at, last_error
search_budget_used, llm_budget_used
created_at, started_at, completed_at
```

상태는 `PENDING`, `RUNNING`, `SUCCEEDED`, `NO_MATCH`, `RETRY`, `FAILED`를 사용한다. 동일 `place_id + source_hash + reason`의 미완료 작업은 하나만 허용한다. lease timeout으로 중단된 작업을 회수하고 exponential backoff와 일일 검색·LLM 예산을 적용한다.

`NO_MATCH`도 성공적인 조사 결과로 저장한다. 새 source hash가 생기거나 재검증 기한이 도래하기 전에는 반복 조사하지 않는다. 초기 backfill은 일일 예산에 맞춰 여러 날에 나누며 중단 후 재개할 수 있어야 한다.

## 검색과 evidence 수집

검색 provider는 공통 port 뒤에 둔다. provider별 query 제한, timeout, retry, rate limit, 결과 schema와 비용을 adapter가 흡수한다. 검색 query는 장소명, 지역명, `촬영지`, `로케이션`, 드라마·영화·뮤직비디오 관련 용어를 조합하며 동일 query 결과는 TTL cache한다.

검색 결과는 URL, canonical URL, 제목, snippet 또는 허용된 본문 발췌, publisher, 게시일, 관측 시각, provider를 저장한다. canonical URL과 content fingerprint로 중복을 제거한다. 공식 제작사·방송사·아티스트, 한국관광공사·지자체·공공기관, 신뢰 가능한 언론·관광 매체 순으로 출처 등급을 둔다. 검색 순위나 domain 등급만으로 작품 연결을 확정하지 않는다.

## LLM 사용 경계

LLM 입력에는 candidate 장소 정보와 수집된 evidence만 포함한다. LLM은 다음 값을 구조화한다.

- `mediaType`: `K_DRAMA`, `CINEMA`, `KPOP`
- 정규화된 작품명과 선택적 아티스트·제작 주체
- 공개 연도
- 등장 장면·회차 또는 촬영 관계 설명
- FE 카드용 짧은 subtitle과 작품 관련 tag
- claim을 지지하는 evidence 식별자
- ambiguity와 confidence 신호

LLM은 새로운 장소, URL 또는 evidence에 없는 사실을 도입할 수 없다. 검색 결과가 명확한 경우 작은 모델을 사용하고, 장소 동명이인이나 작품명 충돌처럼 애매한 경우에만 상위 모델로 escalate한다. 동일 evidence bundle hash와 prompt/schema version 결과는 cache한다.

## 결정론적 검증

일반 코드가 다음 조건을 모두 확인한 뒤 claim을 저장한다.

- 입력 candidate의 canonical place인지
- 인용한 evidence가 실제 검색 adapter 결과에 포함됐는지
- 허용된 URL scheme과 canonical URL인지
- evidence가 장소와 작품의 구체적 관계를 지지하는지
- media type과 필수 필드가 계약에 맞는지
- 동일 장소·작품·매체 claim과 중복되지 않는지
- 차단 domain 또는 삭제·무효화된 evidence가 아닌지

한 출처만으로도 명확한 공식 근거이면 게시할 수 있다. 비공식 출처만 있는 경우 독립된 두 evidence를 요구한다. 검증 실패 항목은 사유와 함께 quarantine하며 공개하지 않는다.

## 데이터 모델

현재 단일 placement 행을 다음 책임으로 분리한다.

- `screen_hanok_works`: 정규화 작품, 매체 유형, 공개 연도, 아티스트·제작 주체
- `screen_hanok_claims`: canonical place와 작품의 등장 관계, 설명, 상태, 최초·마지막 검증 시각
- `screen_hanok_evidence`: URL, 제목, publisher, 게시일, 관측 시각, content fingerprint, 출처 등급
- `screen_hanok_claim_evidence`: claim과 복수 evidence 연결
- `screen_hanok_research_jobs`: 조사 실행 상태와 비용·재시도
- `screen_hanok_publications`: 공개 여부, 대표 순서와 품질·다양성 점수

기존 장소명, category, 좌표, 주소와 이미지는 canonical catalog를 계속 참조한다. 작품 관련 정보는 TourAPI revision 교체와 독립적으로 유지한다. 기존 placement 데이터는 claim/work/evidence로 migration한 뒤 동일 기준으로 재검증한다.

## 공개 API와 FE 다양성

기존 endpoint와 media type 필터의 호환성을 유지하면서 cursor pagination과 다음 필터·metadata를 확장한다.

- media type, 작품, 공개 연도
- 지역과 기존 장소 category
- 소리마루 연결 여부
- 작품 수와 출처 수
- 대표 작품, 추가 작품 개수
- 검증 시각과 공개 가능한 출처 목록

별도의 section endpoint를 고정해서 FE 구성을 제한하지 않는다. 대신 facet count와 안정적인 정렬 신호를 제공해 FE가 `드라마 속 사극 명소`, `K-POP이 담은 한국의 미`, `서울 밖 촬영지`, `소리마루와 함께 듣는 촬영지` 같은 섹션을 조합할 수 있게 한다. 필요하면 후속 계약에서 서버가 추천 section descriptor를 제공한다.

## 품질과 다양성 gate

초기 공개 목표는 고유 장소 100개 이상과 작품-장소 claim 150개 이상이다. 운영 dashboard와 publication 검증에서 다음을 확인한다.

- `K_DRAMA`, `CINEMA`, `KPOP` 각각 고유 장소 20개 이상
- 수도권 외 고유 장소 비율 50% 이상
- 동일 작품이 대표 카드의 10%를 초과하지 않음
- 동일 장소가 한 section의 대표 카드에 중복되지 않음
- 공개 claim은 유효 evidence를 최소 하나 가짐
- 깨진 이미지와 장소 불일치 이미지는 대표 카드에서 제외

수량을 맞추기 위해 검증 기준을 낮추지 않는다. 목표 미달은 publication 실패가 아니라 coverage 경고로 기록하고, 확보된 검증 데이터는 계속 제공한다.

## 실패 처리와 데이터 보존

- 검색 provider 또는 LLM 장애: job을 재시도하고 기존 claim/publication 유지
- batch 일부 실패: 성공한 결과는 누적 저장하고 실패 job만 재시도
- 출처 URL 장애: grace period 동안 claim을 `STALE`로 표시하고 대체 evidence 검색
- 장소 원천 누락: catalog missing 정책을 따르며 claim을 즉시 삭제하지 않음
- 잘못된 기존 7개: evidence 재검증 실패 시 publication에서 제외하되 감사 이력 보존
- hash schema 변경: 새 version으로 전체 재계산하되 작품 claim 재조사는 별도 판단

## 관측성과 비용 통제

실행마다 후보 수, cache hit, 검색 성공, evidence 수, LLM 호출·token, 검증 통과, quarantine, 게시 수를 남긴다. media type·지역·category·출처 domain별 coverage와 장소당 평균 비용을 집계한다. 검색·LLM provider별 일일 budget, concurrency, circuit breaker를 둔다.

민감한 key, 검색 원문 전체, provider 내부 응답은 안전 로그에 남기지 않는다. 공개 가능한 evidence 필드와 운영 진단용 식별자만 구조화 로그에 기록한다.

## 검증

- canonicalization과 hash schema의 결정성, volatile field 제외 테스트
- PostgreSQL snapshot set diff의 ADDED/CHANGED/UNCHANGED/MISSING 통합 테스트
- 동일 source hash에서 job 중복 생성 방지 테스트
- 검색 cache, provider timeout·429·5xx 재시도 테스트
- grounding/evidence 밖 URL과 알 수 없는 place/work 차단 테스트
- 공식 단일 출처와 비공식 복수 출처 정책 테스트
- LLM·검색 장애 후 기존 publication 보존 테스트
- 기존 placement migration과 재검증 테스트
- 초기 backfill 중단·재개 및 일일 budget 테스트
- API cursor, filter, facet count, 다중 작품·복수 출처 계약 테스트
- 고유 장소·매체·지역 다양성 report fixture 테스트

## 단계적 적용

1. 현재 운영 데이터와 비용을 읽기 전용으로 계측하고 기존 7개를 export한다.
2. hash diff와 research job/evidence/claim schema를 추가하되 기존 API는 유지한다.
3. 검색 우선 worker를 shadow mode로 실행해 기존 결과와 비교한다.
4. 기존 catalog backfill을 일일 budget 안에서 수행하고 100개 장소·150개 claim 목표를 추적한다.
5. 기존 7개를 재검증하고 새 read model로 API를 전환한다.
6. 14일 주기 TourAPI 증분 수집과 작품 리서치 통합 run, 30일 주기 evidence URL 확인을 활성화한다.
7. FE가 새 metadata로 여러 섹션을 구성하도록 계약 문서를 전달한다.

## 범위 밖

- 기존 장소 category 체계 개편
- FE 화면 자체 구현
- 출처 없는 LLM 기억만으로 작품 연결 생성
- 초기 단계의 별도 GPU·로컬 LLM 운영
- 검색 provider 한 곳에 종속된 domain model
- TourAPI가 제공하지 않는 ETag·change feed 가정
