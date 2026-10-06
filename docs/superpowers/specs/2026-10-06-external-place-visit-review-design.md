# 외부 장소 기반 VisitReview 생성 설계

## 문서 상태

- 관련 Issue: [#643 온기 한 줄 남기기 — 모든 장소 지원 API 요청](https://github.com/YRootLab/OnMaru-backend/issues/643)
- 상태: 장소 입력과 태그 validation 정책 합의 완료, 나머지 API·동시성·운영 정책 검토 필요
- 범위: Kakao 장소 검색 결과로 VisitReview를 생성하는 API 계약과 서버 경계
- 범위 밖: 백엔드의 Kakao Local API 재조회, 관리자 장소 검수 화면, 기존 장소 자동 병합

## 배경

현재 `POST /api/v1/places/{placeId}/visit-reviews`는 OnMaru Catalog가 이미 발급한
공개 `placeId`만 받는다. 활성 Catalog revision에 포함되고 `visit_review_eligible=true`인
장소만 조회하므로 FE가 Kakao Places API에서 선택한 숫자형 Kakao 장소 ID는 `404`가 된다.

FE는 한옥이나 기존 관광 Catalog에 한정하지 않고 대한민국 내 모든 Kakao 검색 장소에
온기를 남겨야 한다. MVP에서는 백엔드가 Kakao API를 다시 호출하지 않고 FE가 전달한 장소
정보를 정책에 따라 검증한다. FE 검증은 빠른 사용자 안내를 담당하고 BE 검증은 우회·변조
요청을 막는 최종 권위다.

## 목표와 성공 조건

1. 인증된 사용자가 대한민국 서비스 영역 안의 Kakao 장소에 VisitReview를 생성할 수 있다.
2. Kakao 외부 ID와 OnMaru 내부 UUID·공개 `placeId`를 분리한다.
3. 동일 Kakao 장소가 동시 또는 반복 요청으로 중복 생성되지 않는다.
4. FE 전달 장소가 정책을 위반하면 기계 판독 가능한 오류를 반환한다.
5. 태그는 FE와 BE가 같은 정책으로 검증하며 DB에는 canonical value만 저장한다.
6. 기존 Catalog 기반 VisitReview 조회·좋아요·신고·삭제 계약은 유지한다.

## 선택한 접근

`POST /api/v1/visit-reviews`를 외부 장소 기반 생성 endpoint로 추가한다. 기존
`POST /api/v1/places/{placeId}/visit-reviews`는 이미 등록된 OnMaru 장소에 대한 생성
endpoint로 유지한다. 기존 path의 `placeId` 의미를 Kakao ID로 바꾸지 않는다.

백엔드는 FE가 전달한 Kakao 장소를 다음 순서로 처리한다.

1. 장소 필드와 후기 필드를 정규화·검증한다.
2. 좌표가 대한민국 MVP 서비스 영역을 명백히 벗어나면 거절한다.
3. 기존 행정경계로 canonical `regionCode`를 해석한다.
4. `(provider=KAKAO, externalId)`로 기존 장소를 찾는다.
5. 없으면 내부 Catalog identity와 안정적인 `p-...` 공개 ID를 생성한다.
6. 생성·조회한 장소 snapshot으로 VisitReview를 저장한다.
7. 생성된 VisitReview 응답에는 Kakao ID가 아니라 OnMaru 공개 `placeId`를 반환한다.

외부 API를 재조회하지 않았다는 신뢰 수준을 구분할 수 있도록 장소 provenance는
`CLIENT_ASSERTED` 의미를 보존한다. 이 값의 구체적인 컬럼 또는 enum 형태는 구현 계획에서
현재 Catalog source model과 함께 결정한다.

## API 요청 계약

```http
POST /api/v1/visit-reviews
X-CSRF-TOKEN: <token>
Idempotency-Key: <uuid>
Content-Type: application/json
```

```json
{
  "place": {
    "provider": "KAKAO",
    "externalId": "123456789",
    "name": "대청댐",
    "lat": 36.4952,
    "lng": 127.4981
  },
  "text": "조용하고 경치가 좋았어요.",
  "mood": "한적",
  "score": 4,
  "tags": ["#힐링", "#야경"]
}
```

`provider`는 MVP에서 `KAKAO`만 허용한다. 이를 요청에 명시해 외부 ID namespace를
분리하고 후속 공급자를 추가할 때 ID 충돌을 피한다.

## 장소 validation 정책

### 기본 필드

- `externalId`: trim 후 비어 있으면 안 된다. 숫자형 JSON이 아니라 문자열로 받는다.
- `name`: NFC와 trim을 적용한 뒤 비어 있으면 안 되며 길이 상한을 둔다.
- `lat`: 유한한 숫자이며 `-90..90`이어야 한다.
- `lng`: 유한한 숫자이며 `-180..180`이어야 한다.
- `provider`: `KAKAO`만 허용한다.

장소명과 좌표는 FE가 Kakao 검색 결과에서 전달한 snapshot이다. 같은 외부 ID의 기존
장소가 있으면 요청값으로 기존 canonical 장소를 자동 갱신하지 않는다.

### 대한민국 서비스 영역

빠른 1차 판정은 대한민국과 부속 도서를 넉넉히 포함하는 bounding box를 사용한다.

```text
32.0 <= lat <= 39.5
123.0 <= lng <= 132.0
```

이 범위는 국가 경계를 정밀하게 증명하는 수단이 아니라 대한민국을 명백히 벗어난 요청을
거르는 MVP guard다. bbox 통과 후 기존 Point-in-Polygon 행정경계 해석을 시도한다.

- bbox 밖: 요청을 거절한다.
- bbox 안이고 region 해석 성공: canonical `regionCode`를 저장한다.
- bbox 안이지만 region 해석 실패: 실제 섬·경계·경계 자료 누락을 고려해 장소와 후기
  생성을 허용하고 `kr-unassigned`로 분류한다.

`kr-unassigned`는 지역별 집계에 포함하지 않고 unassigned 지표로 관찰한다. 실제 Catalog
region FK와의 표현 방법은 다음 설계 단계에서 확정해야 한다.

### 반복 요청과 충돌

동일 `(KAKAO, externalId)`가 이미 존재하면 저장된 장소의 공개 ID·이름·좌표·지역을
후기 snapshot에 사용한다. FE 전달값은 기존 장소를 덮어쓰지 않는다.

기존 좌표와 요청 좌표의 차이가 허용 임계값을 크게 넘으면
`409 PLACE_IDENTITY_CONFLICT`를 반환한다. 정확한 거리 임계값과 장소 이전 처리 정책은
다음 설계 단계에서 확정한다.

## 태그 canonicalization과 validation 정책

`#`은 화면 표현이며 저장 데이터가 아니다. DB와 VisitReview 응답에는 `힐링`, `야경`처럼
canonical value를 사용한다. FE는 표시할 때 `#`을 붙인다.

### 허용하는 정규화

1. Unicode NFC 적용
2. 앞뒤 공백 제거
3. 선행 `#`이 정확히 하나면 제거
4. 연속된 내부 공백을 한 칸으로 축소
5. 영문은 소문자로 변환
6. 정규화 후 동일한 태그는 하나로 중복 제거

### 검증 규칙

- 한 요청에 최대 5개
- canonical tag 하나당 1~15 code points
- 완성형 한글, 영문, 숫자 허용
- 여러 단어 사이에는 공백 한 칸만 허용
- `#`을 제외한 특수문자, 줄바꿈, 탭 금지
- 선행 `#`은 최대 한 개만 허용
- 정규화 결과가 빈 문자열이면 금지

개념적인 canonical pattern은 다음과 같다.

```regex
^[가-힣a-z0-9]+(?: [가-힣a-z0-9]+)*$
```

| 입력 | 결과 |
| --- | --- |
| `#힐링` | `힐링` 저장 |
| `힐링` | `힐링` 저장 |
| `  #힐링  ` | `힐링` 저장 |
| `#야경   명소` | `야경 명소` 저장 |
| `####하이` | 거절 |
| `#하이.` | 거절 |
| `# 힐링` | 거절 |
| `#` | 거절 |
| 줄바꿈 또는 탭 포함 | 거절 |
| `Healing` | `healing` 저장 |
| `#힐링`, `힐링` | `힐링` 하나 저장 |

잘못된 태그가 하나라도 있으면 유효한 태그만 골라 저장하지 않고 후기 생성 요청 전체를
원자적으로 거절한다. 중복만 자동 제거하며 다른 문법 오류는 자동 수정하지 않는다.

## FE와 BE 책임

### FE

- 입력 시 공통 정책으로 즉시 validation한다.
- 15자 초과, 허용하지 않는 문자, 5개 초과, 중복을 제출 전에 안내한다.
- validation 오류가 있으면 제출을 막는다.
- `#`은 입력 호환 또는 표시 장식으로 처리하고 API canonical 계약을 따른다.
- 서버 오류의 `details.field`에 해당하는 입력을 강조한다.

권장 안내 문구:

- `태그는 한글, 영문, 숫자와 띄어쓰기만 사용할 수 있어요.`
- `태그는 15자까지 입력할 수 있어요.`
- `태그는 최대 5개까지 추가할 수 있어요.`
- `이미 추가한 태그예요.`

### BE

- FE validation 결과를 신뢰하지 않고 같은 정책을 다시 적용한다.
- canonical value만 저장한다.
- 정책 위반 시 후기와 장소를 모두 저장하지 않는다.
- 원문 태그를 오류 응답이나 로그에 불필요하게 복사하지 않는다.
- OpenAPI를 validation 기준 계약으로 유지하고 runtime DTO·테스트와 동기화한다.

FE와 BE가 서로 다른 정규식을 독립적으로 해석하지 않도록 OpenAPI schema에 길이,
개수, canonical pattern, 예제를 기록한다. `#` 제거와 공백 축소처럼 JSON Schema로 표현하기
어려운 정규화는 schema description과 FE 전달 문서에 명시한다.

## 오류 계약

장소 또는 태그 validation이 실패하면 장소와 후기를 저장하지 않는다. 오류 응답은 기존
`ApiErrorResponse` envelope를 유지한다.

```json
{
  "schemaVersion": "1.2",
  "code": "VALIDATION_ERROR",
  "message": "태그 형식이 올바르지 않습니다.",
  "requestId": "req-...",
  "details": {
    "field": "tags[1]",
    "reason": "INVALID_TAG_FORMAT",
    "rule": "한글, 영문, 숫자와 단어 사이 공백만 사용할 수 있습니다."
  }
}
```

| 조건 | HTTP | code | details.reason |
| --- | ---: | --- | --- |
| 필수 장소 필드 누락 | 400 | `VALIDATION_ERROR` | `REQUIRED` |
| 좌표 형식·일반 범위 오류 | 400 | `VALIDATION_ERROR` | `INVALID_COORDINATE` |
| 대한민국 서비스 범위 밖 | 422 | `PLACE_OUTSIDE_SERVICE_AREA` | `OUTSIDE_SERVICE_AREA` |
| 태그 5개 초과 | 400 | `VALIDATION_ERROR` | `TOO_MANY_TAGS` |
| 빈 태그 | 400 | `VALIDATION_ERROR` | `TAG_REQUIRED` |
| 태그 15자 초과 | 400 | `VALIDATION_ERROR` | `TAG_TOO_LONG` |
| 태그 문법 위반 | 400 | `VALIDATION_ERROR` | `INVALID_TAG_FORMAT` |
| 기존 외부 ID와 현저히 다른 좌표 | 409 | `PLACE_IDENTITY_CONFLICT` | `LOCATION_MISMATCH` |

중복 태그는 정규화 후 제거하므로 서버 오류로 만들지 않는다. 같은 장소의 동시 최초 등록은
DB unique constraint와 transaction 재조회로 정상 수렴시키며 사용자에게 충돌 오류를
노출하지 않는다.

## 응답과 기존 계약 호환성

성공 응답은 기존 `VisitReview` 형태를 유지한다. `placeId`에는 Kakao ID가 아닌 OnMaru
공개 ID를 반환하고, `placeName`, `lat`, `lng`는 저장된 장소 snapshot을 사용한다.

```json
{
  "id": "review-abc123",
  "placeId": "p-kakao-123456789",
  "placeName": "대청댐",
  "lat": 36.4952,
  "lng": 127.4981,
  "text": "조용하고 경치가 좋았어요.",
  "mood": "한적",
  "score": 4,
  "tags": ["힐링", "야경"],
  "likeCount": 0,
  "likedByMe": false,
  "mine": true,
  "createdAt": "2026-10-05T12:00:00Z",
  "author": {
    "displayName": "온마루 사용자"
  }
}
```

## transaction과 멱등성 원칙

- 장소 resolve-or-create와 후기 insert는 하나의 application transaction 경계에서 처리한다.
- `(provider, externalId)`에는 DB unique constraint를 둔다.
- 동시 insert unique violation은 기존 장소를 재조회해 이어서 처리한다.
- 같은 회원·endpoint·`Idempotency-Key`·동일 payload 재전송은 동일 후기 응답을 반환한다.
- 같은 idempotency key에 다른 장소 또는 후기 payload가 오면 기존 `409
  IDEMPOTENCY_CONFLICT`를 유지한다.
- idempotency fingerprint에는 정규화 전후 중 어느 payload를 사용할지 계약 테스트로
  고정한다. 사용자가 의미상 같은 입력을 재전송했을 때 같은 결과가 되도록 canonical
  command 기준 fingerprint를 우선 검토한다.

## 보안과 관찰 가능성

- 장소명·태그·정확한 좌표를 metric label에 넣지 않는다.
- validation 실패 로그에는 `requestId`, 오류 code·reason, provider만 기록한다.
- `PLACE_OUTSIDE_SERVICE_AREA`, `PLACE_IDENTITY_CONFLICT`, `kr-unassigned` 발생 건수를
  low-cardinality metric으로 관찰한다.
- 신규 장소 생성률과 기존 장소 재사용률을 구분해 비정상 자동 요청을 탐지한다.
- 인증, CSRF, 요청 크기 제한, rate limit과 기존 멱등성 정책을 그대로 적용한다.

## 테스트 범위

### 단위 테스트

- 장소 필수값, 유한 좌표, 일반 좌표 범위, 대한민국 bbox 경계
- region resolve 성공과 `kr-unassigned` fallback
- 태그 표의 정상화·거절 사례 전체
- Unicode NFC와 code point 길이
- 정규화 후 중복 제거
- 기존 장소가 요청 snapshot으로 덮어써지지 않음

### 통합 테스트

- 신규 Kakao 장소, 공개 ID, 후기의 원자적 생성
- 동일 Kakao ID의 순차·동시 요청이 하나의 장소로 수렴
- 장소 생성 뒤 후기 insert 실패 시 전체 rollback
- 기존 Catalog 장소와 외부 identity 연결 정책
- idempotency replay와 payload conflict
- region 미해결 장소의 조회·지역 집계 제외

### 계약 테스트

- OpenAPI 요청·응답·오류 fixture
- FE가 필요한 `details.field`와 `details.reason`
- `tags` 응답이 `#` 없는 canonical value임을 검증
- 실제 Controller DTO와 frozen OpenAPI의 정합성

## 다음 설계 단계

다음 항목을 순서대로 결정해야 구현 계획을 만들 수 있다.

1. **외부 장소 영속 모델**: 기존 `catalog_place_sources`와 dataset revision을 재사용할지,
   온기 입력용 external identity table을 둘지 결정한다. 이는 transaction 경계와 기존
   Catalog 공개 정책을 좌우하므로 다음 최우선 결정이다.
2. **기존 Catalog 장소와 중복 처리**: Kakao 장소와 TourAPI 장소를 즉시 병합할지,
   별도 identity로 유지하고 후속 alias/merge를 허용할지 결정한다.
3. **`kr-unassigned` 표현**: nullable region, 전용 pseudo-region, 별도 resolution status 중
   현재 FK와 집계에 가장 안전한 표현을 선택한다.
4. **외부 ID 위치 충돌 기준**: 허용 거리와 실제 장소 이전·Kakao 데이터 변경 시 갱신
   절차를 결정한다.
5. **장소명과 ID validation 상한**: 실제 Kakao fixture를 근거로 code point 길이와 ID
   pattern을 고정한다.
6. **공개 ID 발급 방식**: 추측 가능한 `p-kakao-{id}` 또는 opaque ID 중 개인정보·공급자
   결합도·운영 편의성을 비교한다.
7. **rate limit과 abuse 정책**: 회원당 신규 장소 생성과 후기 작성 제한, 반복 invalid
   요청 대응을 정한다.
8. **OpenAPI 전환과 FE rollout**: 기존 endpoint 유지 기간, FE feature flag, 실패 rollback,
   staging fixture와 배포 순서를 정한다.

## 권장 다음 결정

외부 장소를 기존 revision 기반 Catalog에 즉시 게시하면 단일 후기 생성 요청이 dataset
revision과 publication까지 소유하게 되어 결합도가 커진다. 반대로 Community가 Catalog
identity를 임의 생성하면 기존 ADR의 Catalog 소유권을 침범한다.

따라서 다음 논의에서는 **Catalog 모듈이 소유하는 경량 external-place registry를 두고,
검증된 정규 ingestion 장소와 사용자 입력 장소를 provenance로 구분하는 방안**을 우선안으로
검토한다. VisitReview는 registry port만 호출하고 Catalog 테이블 구현 세부사항을 알지
않도록 경계를 유지한다.
