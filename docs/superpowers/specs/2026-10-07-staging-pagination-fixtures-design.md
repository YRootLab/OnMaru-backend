# 스테이징 pagination·지도·온기 합성 fixture 확장 설계

## 배경과 목표

온디맨드 스테이징은 현재 서로 연결된 장소 4건, 공개 온기 후기 2건, Odii story 3건을 제공한다. 장소 상세와 후기, Odii 연결을 확인하기에는 충분하지만, FE가 `staging-api.onmaru.site`를 사용해 cursor pagination을 두세 번 연속 호출하거나 지도 줌 레벨별 집계 전환을 확인하기에는 데이터가 부족하다.

Issue #675에서는 운영 데이터를 복제하지 않고 다음 상태를 만든다.

- 지도 공개 장소를 정확히 100건 제공한다.
- fixture 소유 공개 온기 후기와 공개 Odii story를 각각 정확히 65건 제공한다. 사용자 공개 후기가 추가되면 온기 API 전체 합계와 페이지 수는 증가할 수 있다.
- `limit=30` 기준으로 30건, 30건, 나머지 건을 반환하는 3페이지 흐름을 재현한다.
- 여러 권역과 좌표 군집을 사용해 `PLACE`, `CLUSTER`, `DISTRICT`, `REGION` 지도 표현을 검증한다.
- 기존 FE 코드와 운영 데이터 및 운영 설정은 변경하지 않는다.

## 선택한 접근

기존의 수작업 기준 fixture 4건은 장소 상세, 이미지 누락, 후기 숨김, Odii 연결 같은 의미 있는 시나리오로 유지한다. 나머지 데이터는 PostgreSQL `generate_series`와 결정적 계산식으로 생성한다.

100건을 모두 개별 SQL 행으로 작성하면 각 행은 읽기 쉽지만 seed 파일이 비대해지고 ID, 좌표, projection 간 참조를 수정하기 어렵다. 별도 JSON 또는 생성 스크립트는 유연하지만 스테이징 시작 경로에 새로운 런타임 의존성을 추가한다. `generate_series`는 기존 `psql` seed 절차 안에서 동작하고, 같은 입력으로 항상 동일한 데이터와 순서를 만들 수 있어 현재 구조에 가장 적합하다.

## 데이터 모델과 분포

### 장소와 지도 projection

기존 장소 4건에 생성 장소 96건을 추가해 공개 장소와 `map_place_read_projection`을 정확히 100건으로 맞춘다. UUID, public ID, source ID, 이름, `sort_key`, 좌표는 series index에서 결정적으로 계산한다.

장소는 최소 네 개 권역에 배치한다. 각 권역에는 서로 가까운 좌표 군집과 떨어진 군집을 함께 둔다. 실제 관광지나 운영 데이터로 오인하지 않도록 이름과 주소에는 `스테이징 합성`을 명시하고 좌표만 실제 지도 범위 안에 둔다. 권역별 region row와 `sido_code`·`sigungu_code`를 연결하고, projection count도 실제 생성 결과에서 집계한다.

카테고리는 `SPOT`, `CAFE`, `MARKET`을 균등하게 순환하고 기존 `HANOK` 상세 시나리오를 유지한다. 일부 행은 이미지와 선택 필드가 있고 일부는 없도록 해 카드 fallback도 확인한다. 기존 네 장소의 public ID는 바꾸지 않아 상세·후기·Odii smoke 경로를 보존한다.

### 온기 후기

기존 공개 후기 2건은 유지하고 생성 공개 후기 63건을 추가해 총 65건으로 맞춘다. 별도의 숨김 후기는 공개 65건에 포함하지 않는다. 공개 후기는 여러 장소와 권역에 분산하고 `created_at DESC, id DESC` keyset 순서가 항상 같도록 고정 시각과 UUID를 사용한다.

추가 공개 사용자 후기가 없는 기준 상태에서 전역 온기 목록은 `limit=30`으로 30/30/5건을 반환해야 한다. 마지막 페이지는 `nextCursor=null`이어야 하며 세 페이지의 review ID 합집합은 공개 fixture 65건과 정확히 일치해야 한다. 사용자 후기가 추가된 DB에서는 반환 cursor를 끝까지 순회하고 fixture 65개와 추가 사용자 데이터를 구분해 포함·중복·합계를 검증한다. 장소별 후기 화면도 pagination을 검증할 수 있도록 기준 장소 하나에는 공개 후기를 31건 이상 연결하고, 나머지는 여러 장소에 분산한다.

### Odii story

기존 연결 story 3건을 유지하고 공개 story 62건을 생성해 총 65건으로 맞춘다. spot, story version, 공개 상태, 고정 `publishedAt`과 story ID를 함께 생성한다. 대표 장소와 spot 연결은 기존 상세 smoke를 유지하고, 생성 story는 목록 pagination 검증에 집중한다.

Odii 목록도 `limit=30` 기준 30/30/나머지 페이지가 되어야 한다. 세 페이지에서 ID 중복이 없어야 하고 `totalCount`, `hasMore`, `nextCursor`가 실제 공개 row 수와 일치해야 한다.

## seed 실행과 안전 경계

기존 `infra/lightsail/staging/start.sh` 흐름을 유지한다. SSH 제한 계정으로 `start`를 실행하면 별도 스테이징 PostgreSQL과 Flyway가 준비된 뒤 `seed.sql`이 실행되고 Spring API가 기동된다. FE는 이미 설정된 `https://staging-api.onmaru.site`를 사용하므로 FE 변경은 필요 없다.

모든 생성 INSERT는 고정 키와 `ON CONFLICT`를 사용한다. seed를 두 번 실행한 뒤 장소 100건, 공개 후기 65건, 공개 story 목표 수가 그대로여야 한다. `current_database() = 'onmaru_staging'` 검사와 운영 원천·회원·세션 데이터를 포함하지 않는 경계는 유지한다.

생성 fixture가 제거되거나 목표 수가 바뀌었을 때 오래된 row가 남아 멱등성 검증을 왜곡하지 않도록, #675가 소유하는 고정 ID 범위의 생성 projection·오디오 row만 참조 무결성 순서에 맞춰 정리한 뒤 다시 생성한다. 생성 후기와 장소 identity·public ID는 upsert해 사용자 참조를 보존한다. 현재 생성 집합 밖의 #675 소유 후기는 `HIDDEN`으로 전환해 공개 집합에서 제외하며, 후기 identity와 좋아요·신고·append-only 검수 이력은 삭제하지 않는다. 기존 수작업 기준 fixture와 사용자가 스테이징에서 작성한 비-fixture 데이터는 삭제하거나 수정하지 않는다.

## 검증 전략

`StagingFixtureTests`를 확장해 실제 PostgreSQL/PostGIS와 Spring JDBC 조회 경로를 검증한다.

1. seed를 두 번 실행하고 fixture 소유 ID 범위의 row 수가 변하지 않는지 확인한다.
2. 공개 지도 장소가 정확히 100건이며 권역·카테고리·좌표 군집이 요구 수를 충족하는지 확인한다.
3. 지도 API가 줌 레벨별 `PLACE`, `CLUSTER`, `DISTRICT`, `REGION` projection을 반환하고 카테고리 필터가 적용되는지 확인한다.
4. 전역 온기 API를 `limit=30`과 반환 cursor로 세 번 호출해 30/30/5, 중복 없음, 누락 없음, 종료 cursor를 확인한다.
5. 기준 장소의 온기 API가 두 페이지 이상을 반환하고 숨김 후기가 포함되지 않는지 확인한다.
6. Odii 목록 API도 같은 방식으로 세 페이지를 순회하고 공개 story 전체 집합과 일치하는지 확인한다.
7. 기존 장소 상세, 이미지 선택 필드, 후기, Odii 연결 smoke가 계속 통과하는지 확인한다.
8. 다른 database 이름에서는 seed가 실패하는 안전장치를 유지한다.

저장소 검증은 대상 PostgreSQL 통합 테스트, staging deploy 계약 테스트, Compose config 검증, 전체 계약 검증과 `git diff --check`를 포함한다. 실제 Lightsail 반영 후에는 SSH `start`로 기동하고 공개 API의 세 페이지 순회와 지도 줌 레벨 smoke를 수행한 뒤 `stop`한다. 운영 API health와 운영 데이터 비변경 여부를 전후에 확인한다.

## 오류 처리와 운영 영향

seed 중 한 단계라도 실패하면 `ON_ERROR_STOP`과 단일 transaction으로 전체 변경을 rollback하고 `start.sh`는 스테이징 컨테이너를 중지한다. 잘못된 cursor는 기존 API 계약대로 validation error를 반환하며 이번 작업에서 cursor 형식이나 기본 API limit은 변경하지 않는다.

추가 row는 온디맨드 스테이징 DB에만 존재한다. 운영 DB, 운영 image 설정, Vercel Production, FE 코드는 변경하지 않는다. 100개 장소와 수십 개 후기·story는 1GB Lightsail의 짧은 FE 검증 범위에서 충분히 작으며, 기존 2시간 자동 종료와 운영 health 선행 검사를 그대로 적용한다.

## 완료 판단

구현 완료는 코드와 로컬 테스트 통과만으로 판단하지 않는다. PR 병합 뒤 검증된 `develop` image를 스테이징에 준비하고, SSH로 기동한 실제 공개 API에서 지도 100건과 온기·Odii 3페이지 순회를 확인해야 한다. 검증 증적에는 workflow URL, 배포 SHA/digest, 페이지별 건수, 중복·누락 결과, 운영 health 전후 결과를 기록한다.
