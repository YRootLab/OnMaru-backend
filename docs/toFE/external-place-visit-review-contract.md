# 외부 장소 온기 작성 FE 계약

Issue #643의 MVP는 FE가 Kakao 장소 검색 결과를 선택한 뒤 `POST /api/v1/visit-reviews`로 장소와 온기를 함께 보내는 방식입니다. BE는 Kakao API를 다시 호출하지 않고 전달값의 형식, 대한민국 서비스 범위, 기존 외부 장소 식별 충돌을 검증합니다.

## 요청과 성공 처리

- 인증 cookie, `X-CSRF-TOKEN`, UUID 형식의 `Idempotency-Key`가 필요합니다.
- `place.provider`는 `KAKAO`, `externalId`는 Kakao 장소 ID, `name`, `lat`, `lng`는 선택 결과를 그대로 보냅니다.
- 성공하면 201과 `Location`을 반환합니다. 응답 `placeId`는 `p-ext-...` 형식의 OnMaru 내부 ID이므로 이후 화면과 조회에는 이 값을 사용합니다.
- 같은 사용자가 같은 canonical 요청과 같은 멱등성 키를 재전송하면 같은 응답을 받습니다.

## 태그 정책

FE는 입력 단계에서 아래 정책을 동일하게 적용하되, BE 검증을 최종 기준으로 둡니다.

- 최대 5개, canonical 태그 하나당 최대 15 Unicode code points
- 한글·영문·숫자와 단일 내부 공백만 허용
- 선행 `#`은 없거나 하나만 허용: `#힐링` → `힐링`
- 앞뒤 공백 제거, NFC 정규화, 연속 ASCII 공백 축약, 영문 소문자화
- 정규화 후 중복은 첫 항목만 유지: `["#힐링", "힐링"]` → `["힐링"]`
- `####하이`, `#하이.`, 빈 태그, 줄바꿈·특수문자는 허용하지 않음

form-time 권장 문구는 `태그는 한글·영문·숫자와 띄어쓰기만 사용할 수 있어요. #은 맨 앞에 하나만 입력해 주세요.`입니다.

## 오류 처리

| HTTP / code | FE 처리 |
| --- | --- |
| 400 `VALIDATION_ERROR` | `details.field`에 해당하는 입력을 강조하고 `details.reason`별 문구 표시. 자동 재시도하지 않음 |
| 409 `PLACE_IDENTITY_CONFLICT` | 장소를 다시 검색·선택하도록 안내. 자동 재시도하지 않음 |
| 409 `IDEMPOTENCY_CONFLICT` | 새 멱등성 키로 사용자가 명시적으로 다시 제출할 때만 재요청 |
| 422 `PLACE_OUTSIDE_SERVICE_AREA` | `현재 대한민국 내 장소만 온기를 남길 수 있습니다.` 표시. 자동 재시도하지 않음 |
| 429 `RATE_LIMITED` | 초안 유지 후 `Retry-After`가 지난 뒤 제출 버튼 활성화 |
| 503 | 초안 유지 후 `Retry-After`가 있으면 준수하고, 없으면 사용자 동의 아래 재시도 |

`details`에는 잘못된 장소명·좌표·외부 ID·태그 원문이 포함되지 않습니다. 태그 오류는 예를 들어 `{"field":"tags[1]","reason":"INVALID_TAG_FORMAT"}` 형태입니다.

## 배포 순서와 fallback

staging에서는 신규 endpoint 사용을 feature flag로 열고 정상 작성, 동일 요청 replay, 400/409/422, 429를 먼저 확인합니다. 실패 시 작성 modal과 입력 초안을 닫거나 삭제하지 말고, 기존 등록 장소용 endpoint로 임의 fallback하지 않습니다. 기존 `POST /places/{placeId}/visit-reviews`는 이미 OnMaru placeId가 있는 화면에서만 계속 사용합니다.
