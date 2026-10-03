# FE 전달서: OnMaru 익명 회원 프로필과 온기 후기 작성자

관련 작업은 [Backend #552](https://github.com/YRootLab/OnMaru-backend/issues/552), [Frontend #292](https://github.com/YRootLab/OnMaru-Frontend/issues/292)다. 카카오 이름·프로필 이미지는 사용하지 않는다. 회원 가입 시 백엔드가 익명 이름과 프로필 ID를 만들고, 사용자는 마이페이지에서 이름·캐릭터·배경을 바꿀 수 있다.

## FE가 소유하는 자산과 고정 ID

백엔드는 이미지 URL과 색상 HEX를 저장하거나 반환하지 않는다. FE가 아래 고정 ID를 로컬 자산·색상 토큰에 매핑한다.

- 캐릭터: `CHARACTER_01`, `CHARACTER_02`, `CHARACTER_03`, `CHARACTER_04`, `CHARACTER_05`, `CHARACTER_06`, `CHARACTER_07`, `CHARACTER_08`, `CHARACTER_09`, `CHARACTER_10`
- 배경: `BACKGROUND_01`, `BACKGROUND_02`, `BACKGROUND_03`, `BACKGROUND_04`, `BACKGROUND_05`, `BACKGROUND_06`, `BACKGROUND_07`, `BACKGROUND_08`, `BACKGROUND_09`, `BACKGROUND_10`

`CHARACTER_*`는 서로 구별되는 온니 캐릭터 10개, `BACKGROUND_*`는 FE가 정한 HEX 색상 10개다. ID 의미와 매핑은 배포 뒤 임의로 바꾸지 않는다. 캐릭터 10개 × 배경 10개의 100개 조합에서 캐릭터 윤곽, 이름, 보조 텍스트가 WCAG AA 수준으로 읽히는지 확인한다.

알 수 없는 미래 ID를 받으면 앱을 깨뜨리지 말고 `CHARACTER_01` 또는 `BACKGROUND_01` 자산으로 표시하고 telemetry/개발 로그를 남긴다. 서버가 프로필을 찾지 못한 legacy·탈퇴 작성자는 `탈퇴한 여행자`, `CHARACTER_01`, `BACKGROUND_01`로 내려준다.

## 내 프로필 조회와 수정

```http
GET /api/v1/members/me
Cookie: __Host-onmaru-session=...
```

```json
{
  "schemaVersion": "1.2",
  "id": "55200000-0000-0000-0000-000000000001",
  "displayName": "고요한 마루 0552",
  "characterId": "CHARACTER_03",
  "backgroundId": "BACKGROUND_07"
}
```

`displayName`, `characterId`, `backgroundId`는 항상 non-null이다. `id`는 UUID 문자열이지만 FE는 형식을 해석하지 않는 opaque 값으로 취급하고, 공개 프로필 비교나 게시글 작성자 식별에 사용하지 않는다.

수정은 부분 수정이다. 바꾸지 않을 필드는 생략하고, `null`을 보내지 않는다. 최소 한 필드를 보내야 한다.

```http
PATCH /api/v1/members/me
Content-Type: application/json
X-CSRF-TOKEN: <GET /auth/csrf로 받은 값>
Cookie: __Host-onmaru-session=...

{
  "displayName": "따뜻한 온니 1004",
  "characterId": "CHARACTER_10"
}
```

성공하면 생략했던 필드까지 포함한 현재 전체 `MemberMe`를 `200`으로 반환한다. 이름은 trim/NFC 처리 후 Unicode code point 기준 2~20자이며 제어문자는 허용하지 않는다. ID 범위는 정확히 `01..10`이다.

- `400 VALIDATION_ERROR`: `details.field`가 `profile`, `displayName`, `characterId`, `backgroundId` 또는 알 수 없는 필드 이름이다.
- `401 AUTH_REQUIRED`: 로그인 세션이 없거나 만료됐다.
- `403 CSRF_INVALID`: CSRF cookie/header가 없거나 일치하지 않는다.

개인 응답은 `Cache-Control: no-store`다. 저장 버튼은 PATCH 진행 중 중복 제출을 막고, 실패 시 서버 응답 이전 상태를 유지한다.

## 온기 후기 작성자 표시

VisitReview 생성 응답과 목록 item에는 다음 객체가 항상 포함된다.

```json
{
  "author": {
    "displayName": "따뜻한 온니 1004",
    "characterId": "CHARACTER_10",
    "backgroundId": "BACKGROUND_01"
  }
}
```

`author.memberId`와 `authorMemberId`는 노출하지 않는다. 프로필은 게시글에 snapshot으로 복사되지 않고 조회할 때 현재 회원 프로필과 결합된다. 따라서 마이페이지에서 수정한 뒤 목록을 다시 조회하면 과거에 쓴 모든 후기에도 최신 이름·캐릭터·배경이 즉시 반영된다. FE는 후기별로 예전 작성자 정보를 캐시하지 않고, 목록 응답의 `author`를 source of truth로 사용한다.

기계 판독 계약은 [Identity OpenAPI](../contracts/openapi/identity-saved.openapi.yaml)와 [VisitReview OpenAPI](../contracts/openapi/visit-reviews.openapi.json)에 있다.
