# 회원 익명 프로필과 온기 후기 작성자 표시 설계

## 1. 배경과 결정

GitHub Issue #552는 카카오 닉네임과 프로필 이미지를 `GET /api/v1/members/me`에 추가해 달라는 요청에서 시작했다. 사용자와 검토한 결과, 카카오 프로필을 OnMaru 공개 활동에 재사용하지 않고 OnMaru 전용 익명 프로필을 제공하기로 결정했다.

- 카카오 OAuth에서는 로그인 식별에 필요한 provider·issuer·subject만 사용한다.
- 회원은 가입 시 익명 표시 이름, 캐릭터, 배경을 자동 배정받는다.
- 회원은 자신의 표시 이름, 캐릭터, 배경을 수정할 수 있다.
- 온기모드의 공개 방문 후기에는 작성자의 현재 프로필을 표시한다.
- 프로필을 변경하면 과거 후기를 포함한 모든 공개 후기에 즉시 최신 프로필이 반영된다.
- 캐릭터 이미지 파일과 배경 HEX 값은 FE가 소유한다. BE는 고정 식별자와 선택값만 소유한다.

이 결정은 카카오 계정과 공개 활동의 식별 가능성을 낮추고, 이미지 업로드 저장소·악성 파일 검사·삭제 정책을 이번 범위에 들이지 않으면서도 사용자별 개성을 제공한다.

## 2. 범위

### 포함

- 회원 프로필 영속 모델과 기존 회원 backfill
- 신규 회원의 익명 프로필 자동 배정
- `GET /api/v1/members/me` 프로필 응답 확장
- `PATCH /api/v1/members/me` 부분 수정 API
- 온기모드 방문 후기 응답의 최신 작성자 프로필
- OpenAPI, fixture, DB schema 문서, FE 전달 문서 갱신
- BE와 FE 저장소의 관련 GitHub Issue 연결

### 제외

- 카카오 닉네임·프로필 이미지 수집 또는 저장
- 사용자 이미지 업로드와 외부 이미지 URL 입력
- 캐릭터 SVG/WebP 제작
- 배경색 HEX 확정
- 후기 작성 당시 프로필 snapshot 보존
- 프로필 공개 범위를 사용자가 별도로 제어하는 기능

## 3. 자산 계약

BE와 FE가 공유하는 영구 식별자는 다음 20개다.

- 캐릭터: `CHARACTER_01`부터 `CHARACTER_10`
- 배경: `BACKGROUND_01`부터 `BACKGROUND_10`

식별자는 의미나 표시 순서를 담지 않는 안정적인 슬롯이다. FE는 각 `characterId`에 실제 캐릭터 정적 자산을, 각 `backgroundId`에 실제 HEX 색상을 매핑한다. BE는 이미지 URL과 HEX를 응답하지 않는다.

FE는 자산을 교체할 수 있지만 이미 배포된 ID의 의미를 다른 슬롯과 맞바꾸지 않는다. 캐릭터는 투명 배경으로 제작하고 모든 배경 조합에서 식별 가능하도록 대비를 검증한다. 10×10의 모든 조합을 허용해 최대 100개 기본 조합을 제공한다.

## 4. 데이터 모델

`onmaru.identity_member_profiles`를 회원 원장의 일부로 추가한다.

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `member_id` | `uuid` | PK, `identity_members(id)` FK, `ON DELETE CASCADE` |
| `display_name` | `varchar(20)` | NOT NULL, trim된 NFC 문자열, 2~20 code points |
| `character_id` | `varchar(20)` | NOT NULL, `CHARACTER_01`~`CHARACTER_10` |
| `background_id` | `varchar(20)` | NOT NULL, `BACKGROUND_01`~`BACKGROUND_10` |
| `created_at` | `timestamptz` | NOT NULL |
| `updated_at` | `timestamptz` | NOT NULL, `updated_at >= created_at` |

표시 이름은 공개 필드지만 전역 unique로 만들지 않는다. 회원 식별과 권한 판정은 계속 내부 UUID와 서버 계산 `mine`을 사용한다. 동일한 표시 이름은 허용하며 FE는 이름을 사용자 식별 키로 사용하지 않는다.

DB CHECK는 캐릭터·배경 ID 범위와 공백 이름을 방어한다. code point 길이, NFC 정규화, 제어문자·개행 거부는 애플리케이션에서 검증한다.

기본 표시 이름은 `{형용사} {명사} {0000~9999}` 형식이다.

- 형용사: `고요한`, `따뜻한`, `느긋한`, `정겨운`, `포근한`, `산뜻한`, `다정한`, `잔잔한`, `맑은`, `소박한`
- 명사: `마루`, `기와`, `골목`, `달빛`, `솔바람`, `꽃길`, `한지`, `찻잔`, `구름`, `나들이`

기존 회원은 migration에서 UUID hash를 이용해 이름 구성요소, 4자리 숫자, 캐릭터와 배경을 결정적으로 분배한다. 신규 회원은 주입 가능한 난수 생성기로 같은 허용 목록에서 기본값을 만들고, OAuth 최초 연결 transaction에서 프로필 row와 함께 저장한다. 저장된 값은 이후 로그인으로 변경되지 않는다. 표시 이름의 중복은 허용한다.

## 5. API 계약

### 5.1 내 프로필 조회

`GET /api/v1/members/me`

```json
{
  "schemaVersion": "1.2",
  "id": "4ff64c68-ef3d-4762-885f-beb4f3a8c2f1",
  "displayName": "고요한 마루 4821",
  "characterId": "CHARACTER_08",
  "backgroundId": "BACKGROUND_03"
}
```

- 유효한 회원 세션이 없으면 기존과 같이 `401 AUTH_REQUIRED`다.
- 정상 회원은 세 프로필 필드를 항상 non-null로 받는다.
- `profileImageUrl`은 추가하지 않는다.
- `Cache-Control: no-store`를 유지한다.

### 5.2 내 프로필 수정

`PATCH /api/v1/members/me`

```json
{
  "displayName": "한옥 산책자",
  "characterId": "CHARACTER_04",
  "backgroundId": "BACKGROUND_09"
}
```

- 세 필드는 모두 optional이며 전달된 필드만 변경한다.
- 최소 한 필드는 필요하다.
- 성공 시 `200`과 갱신된 전체 `MemberMe`를 반환한다.
- cookie 기반 변경 요청이므로 기존 CSRF 보호를 적용한다.
- 세션이 없거나 ACTIVE 회원이 아니면 `401 AUTH_REQUIRED`다.
- 이름 길이·문자 또는 ID가 유효하지 않으면 `400 VALIDATION_ERROR`와 `details.field`를 반환한다.
- 존재하지 않는 프로필은 정상 회원 불변식 위반으로 취급하며 조용히 기본값을 재생성하지 않는다.

### 5.3 온기모드 방문 후기

방문 후기 생성 응답과 목록 item에 `author`를 추가한다.

```json
{
  "id": "...",
  "placeId": "...",
  "text": "골목이 조용하고 좋았어요.",
  "author": {
    "displayName": "한옥 산책자",
    "characterId": "CHARACTER_04",
    "backgroundId": "BACKGROUND_09"
  },
  "mine": false,
  "likeCount": 3,
  "likedByMe": false
}
```

공개 응답에 내부 `memberId`, 카카오 subject 또는 이메일을 포함하지 않는다. 후기 row에는 프로필 snapshot을 복제하지 않는다. 조회 시 현재 프로필을 결합하므로 프로필 변경이 기존 후기에도 즉시 반영된다.

후기 목록은 페이지에 포함된 작성자 UUID 집합으로 프로필을 한 번에 조회해 N+1 query를 방지한다. Community query 계층은 `VisitReviewAuthorProfileLookup` port와 공개에 필요한 최소 값만 알고, Identity 구현 세부사항에는 의존하지 않는다. JDBC adapter는 한 번의 batch query로 프로필을 제공한다.

## 6. 컴포넌트와 데이터 흐름

### 최초 로그인

1. Kakao adapter가 provider·issuer·subject만 `ExternalIdentity`로 만든다.
2. OAuth service가 익명 이름과 캐릭터·배경 기본값을 생성한다.
3. Identity store가 신규 member, external account, member profile을 한 transaction에서 저장한다.
4. 기존 external account라면 저장된 프로필을 유지하고 세션만 발급한다.

### 프로필 수정

1. Web 계층이 세션과 CSRF를 검증한다.
2. Profile service가 이름을 trim·NFC 정규화하고 모든 입력을 검증한다.
3. Store가 ACTIVE 회원의 프로필을 원자적으로 갱신한다.
4. Controller가 저장된 전체 프로필을 반환한다.

### 후기 조회

1. Community store가 기존 조건과 cursor로 후기 페이지를 조회한다.
2. Query service가 해당 페이지의 작성자 UUID를 deduplicate한다.
3. Author profile lookup이 한 번의 batch query로 최신 프로필을 조회한다.
4. Query service가 각 후기의 공개 `author` 객체를 조립한다.

## 7. 오류와 운영 경계

- 허용되지 않은 캐릭터·배경 ID는 저장하지 않는다.
- 표시 이름은 trim 후 NFC로 정규화하며 빈 값, 개행, 제어문자, 2자 미만, 20 code points 초과를 거부한다.
- 사용자 입력 이름은 HTML로 해석하지 않고 JSON 문자열로만 전달한다. FE도 text node로 렌더링한다.
- 공개 표시 이름은 신고·운영 정책의 대상이 될 수 있다. 이번 변경에서는 기존 후기 신고 흐름을 유지하고 프로필 전용 신고 기능은 추가하지 않는다.
- 프로필 변경은 최신 읽기에 즉시 반영하되 CDN이나 장기 응답 cache를 두지 않는다.
- 회원 탈퇴 cleanup은 profile row를 FK cascade로 삭제한다. 후기 lifecycle은 기존 탈퇴 정책을 따른다.

## 8. 고려한 대안

### 카카오 프로필 사용

구현은 단순하지만 사용자가 예상하지 못한 실명·얼굴 노출로 이어질 수 있어 채택하지 않았다.

### FE localStorage에만 프로필 저장

기기 간 일관성이 없고 공개 후기 작성자 정보를 서버가 신뢰할 수 없어 채택하지 않았다.

### BE가 이미지 URL과 HEX 저장

FE 디자인 변경이 회원 데이터 migration으로 이어지고 서버가 표현 세부사항을 소유하게 되어 채택하지 않았다.

### 숫자만 저장

가능하지만 API 로그와 문서에서 필드 의미가 약하다. 고정 문자열 ID는 타입과 범위를 드러내면서도 FE 디자인 의미를 강제하지 않아 채택했다.

### 후기 작성 시 프로필 snapshot 저장

과거 표현을 보존할 수 있지만 사용자가 이름을 바꿔도 기존 게시물에 반영되지 않는다는 결정과 충돌해 채택하지 않았다.

## 9. 테스트와 검증

구현은 TDD로 진행한다.

- Profile generator: 허용 ID만 생성하고 익명 이름 규칙을 만족한다.
- Profile service: 부분 수정, NFC·trim, 길이·개행·제어문자·ID 검증을 확인한다.
- In-memory identity: 신규 회원 프로필 생성과 재로그인 시 보존을 확인한다.
- JDBC migration: 기존 회원 backfill, FK cascade, CHECK, timestamp 불변식을 실제 PostgreSQL에서 확인한다.
- JDBC identity: member·external account·profile 원자 생성과 batch profile 조회를 확인한다.
- Web boundary: `GET/PATCH /members/me`, CSRF, 401, 400, 전체 응답을 확인한다.
- VisitReview query: 최신 프로필 반영, 내부 회원 ID 비노출, 여러 후기의 batch lookup을 확인한다.
- Contract: identity와 visit-review OpenAPI/fixture 및 FE 전달 문서 정합성을 검증한다.
- 회귀: identity, community, Spring API 대상 테스트 후 전체 Gradle test와 repository contract 검증을 실행한다.

## 10. FE 전달 사항

FE 후속 Issue에는 다음 acceptance criteria를 기록한다.

- `CHARACTER_01`~`CHARACTER_10`에 대응하는 캐릭터 자산 10개를 제공한다.
- `BACKGROUND_01`~`BACKGROUND_10`에 대응하는 배경 HEX 10개를 제공한다.
- 캐릭터는 투명 배경이며 100개 조합에서 충분한 대비를 가진다.
- 마이페이지가 `GET /members/me`의 세 프로필 필드를 표시한다.
- 마이페이지에서 이름·캐릭터·배경을 선택하고 `PATCH /members/me`로 저장한다.
- 온기모드 후기의 `author`를 캐릭터·배경·이름으로 렌더링한다.
- 알 수 없는 ID는 안전한 기본 자산으로 fallback하고 오류 관측을 남긴다.
- 카카오 닉네임·프로필 이미지와 `profileImageUrl`에 의존하지 않는다.

FE 구현과 자산 제작은 [OnMaru-Frontend #292](https://github.com/YRootLab/OnMaru-Frontend/issues/292)에서 추적한다. BE #552에도 변경된 범위와 FE Issue 링크를 기록해 두 저장소의 계약을 상호 연결했다.
