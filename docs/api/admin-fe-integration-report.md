# Admin API FE 연동 보고서

작성일: 2026-09-29
관련 Issue: #375
대상: OnMaru 관리자 콘솔 FE

## 결론

관리자 콘솔은 일반 사용자 카카오 로그인 화면과 분리된 `/admin` 영역으로 구현한다. `/admin`에 직접 진입하면 FE route guard가 관리자 access token 유무를 확인하고, 없으면 `/admin/login`으로 이동시킨다.

관리자 인증은 이메일·비밀번호 기반이며 카카오 OAuth를 사용하지 않는다. 백엔드는 짧은 수명의 access JWT와 HttpOnly refresh cookie를 함께 사용한다. FE는 refresh cookie를 직접 읽지 않고 refresh endpoint를 호출해야 한다.

## FE에서 구현할 화면과 라우트

| 라우트 | 화면 | 필수 기능 |
|---|---|---|
| `/admin/login` | 관리자 로그인 | 이메일·비밀번호 입력, 로그인 실패 메시지, 재시도 |
| `/admin` | Dashboard | 기간 필터, 후기/신고 통계, pipeline 상태 |
| `/admin/reviews` | 후기 검수 | 상태 필터, 신고 여부, 숨김·게시·삭제 처리 |
| `/admin/reports` | 신고 관리 | 신고 사유·상태 확인, 후기 상세 이동 |
| `/admin/users` | 회원 관리 | 회원 상태·가입일·후기 수, 제재 이력 |
| `/admin/curations` | 큐레이션 | category별 포함 여부·badge override |
| `/admin/pipelines` | 데이터 pipeline | 상태 조회, 관리자 실행 요청 |

`EDITOR`는 후기·신고·큐레이션 조회/편집 중심으로 제공하고, `ADMIN` 전용 기능인 회원 제재와 pipeline 실행 버튼은 숨기거나 disabled 처리한다. 최종 권한 판단은 항상 백엔드 응답을 기준으로 한다.

## 인증 흐름

### 초기 진입

1. FE가 `/auth/csrf`를 호출한다.
2. 응답의 `token`과 `headerName`을 메모리에 보관한다.
3. 백엔드가 `__Host-onmaru-csrf` cookie를 설정한다.
4. 관리자 login 요청에 `X-CSRF-TOKEN` 헤더를 붙인다.

CSRF endpoint는 `/api/v1` prefix가 없는 `/auth/csrf`이다. 반면 관리자 API는 `/api/v1` prefix를 사용한다.

응답 예시:

```json
{
  "token": "csrf-token",
  "headerName": "X-CSRF-TOKEN"
}
```

### 로그인

```http
POST /api/v1/auth/admin/login
Content-Type: application/json
X-CSRF-TOKEN: <csrf-token>

{
  "email": "admin@example.com",
  "password": "..."
}
```

성공하면 응답 body의 `accessToken`을 FE 메모리 상태에 저장하고, `Set-Cookie`로 전달된 `__Host-onmaru-admin-refresh` cookie는 브라우저가 보관한다.

access token은 `localStorage`에 저장하지 않는 것을 권장한다. 새로고침 시 token이 사라지는 정책을 택하면 앱 초기화 시 refresh endpoint를 호출해 복구한다.

### 일반 API 호출

```http
Authorization: Bearer <accessToken>
```

상태 변경 요청에는 모두 CSRF header를 추가한다.

- `POST /api/v1/auth/admin/refresh`
- `POST /api/v1/auth/admin/logout`
- `POST /api/v1/admin/reviews/{reviewId}/moderation-actions`
- `POST /api/v1/admin/users/{memberId}/sanctions`
- `DELETE /api/v1/admin/users/{memberId}/sanctions/{sanctionId}`
- `PUT /api/v1/admin/curations/{placeId}`
- `POST /api/v1/admin/pipelines/{dataset}/runs`

### access token 만료

1. API가 `401 AUTH_REQUIRED`를 반환한다.
2. FE가 `POST /api/v1/auth/admin/refresh`를 호출한다.
3. 요청에는 `credentials: include`를 설정해 refresh cookie를 전송한다.
4. 새 access token을 메모리에 반영한다.
5. 원래 요청을 한 번만 재시도한다.
6. refresh도 401이면 token 상태를 제거하고 `/admin/login`으로 이동한다.

동시에 여러 요청이 401을 반환할 수 있으므로 refresh 요청은 single-flight 방식으로 하나만 실행해야 한다.

### 로그아웃

```http
POST /api/v1/auth/admin/logout
Authorization: Bearer <accessToken>
X-CSRF-TOKEN: <csrf-token>
credentials: include
```

성공 후 access token 상태를 제거하고 `/admin/login`으로 이동한다. refresh cookie는 백엔드가 만료시킨다.

## HTTP client 설정

브라우저 요청에는 cross-origin 여부와 관계없이 refresh cookie를 보내야 하므로 HTTP client에 credentials 설정이 필요하다.

```ts
fetch(url, {
  credentials: "include",
  headers: {
    Authorization: `Bearer ${accessToken}`,
    "X-CSRF-TOKEN": csrfToken,
  },
});
```

axios를 사용한다면 `withCredentials: true`를 기본값으로 설정한다. FE origin과 API origin이 다르면 백엔드 CORS에서도 credentials 허용과 명시적 origin 설정이 필요하다.

## 주요 API 목록

| 기능 | Method | Endpoint |
|---|---:|---|
| CSRF 발급 | GET | `/auth/csrf` |
| 관리자 로그인 | POST | `/api/v1/auth/admin/login` |
| Access 갱신 | POST | `/api/v1/auth/admin/refresh` |
| 로그아웃 | POST | `/api/v1/auth/admin/logout` |
| 내 관리자 정보 | GET | `/api/v1/auth/admin/me` |
| Dashboard | GET | `/api/v1/admin/dashboard/summary` |
| 후기 목록 | GET | `/api/v1/admin/reviews` |
| 후기 상태 변경 | POST | `/api/v1/admin/reviews/{reviewId}/moderation-actions` |
| 신고 목록 | GET | `/api/v1/admin/reports` |
| 회원 목록 | GET | `/api/v1/admin/users` |
| 제재 이력 | GET | `/api/v1/admin/users/{memberId}/sanctions` |
| 제재 생성 | POST | `/api/v1/admin/users/{memberId}/sanctions` |
| 제재 해제 | DELETE | `/api/v1/admin/users/{memberId}/sanctions/{sanctionId}` |
| 큐레이션 목록 | GET | `/api/v1/admin/curations` |
| 큐레이션 저장 | PUT | `/api/v1/admin/curations/{placeId}` |
| Pipeline 상태 | GET | `/api/v1/admin/pipelines/{dataset}/status` |
| Pipeline 실행 | POST | `/api/v1/admin/pipelines/{dataset}/runs` |

## 에러 처리

표준 오류 body는 다음 형태다.

```json
{
  "schemaVersion": "1.2",
  "code": "AUTH_REQUIRED",
  "message": "...",
  "requestId": "uuid",
  "details": {}
}
```

| 상태 | 처리 |
|---:|---|
| `400` | 입력값 오류. 폼 validation 또는 query 파라미터 수정 |
| `401 AUTH_REQUIRED` | access token 갱신 시도. refresh도 실패하면 로그인 화면 이동 |
| `403 FORBIDDEN` | 권한 부족. 해당 버튼 숨김/비활성화 |
| `404 NOT_FOUND` | 대상이 삭제됐거나 더 이상 존재하지 않음. 목록 재조회 |
| `409` | 상태 충돌 또는 중복 처리. 최신 상태 재조회 |
| `429` | 잠시 후 재시도. 로그인 버튼 debounce |
| `5xx` | 사용자에게 일시적 장애 안내, 자동 재시도는 제한 |

모든 API 응답의 `requestId`는 장애 문의와 로그 추적에 사용하므로 FE 오류 화면과 telemetry에 함께 기록한다. 비밀번호, access token, refresh cookie 값은 로그에 남기지 않는다.

## 후기 검수 연동 주의사항

후기 상태의 백엔드 canonical 값은 `PUBLISHED`, `HIDDEN`, `REMOVED`다. FE mock에서 사용하는 `DELETED`를 그대로 전송하지 않는다.

검수 요청 예시:

```json
{
  "nextStatus": "HIDDEN",
  "reason": "SPAM_CONFIRMED",
  "note": "광고성 콘텐츠 확인"
}
```

OpenAPI 계약상 `Idempotency-Key`를 함께 보내도록 되어 있으므로 FE는 UUID를 생성해 전송한다. 동일한 버튼을 중복 클릭하지 않도록 요청 중에는 버튼을 잠근다.

## 운영 전 FE 체크리스트

- [ ] `/admin/login` 및 관리자 route guard 구현
- [ ] 앱 초기화 시 `/auth/csrf` 호출
- [ ] 모든 unsafe request에 CSRF header 적용
- [ ] `credentials: include` 또는 `withCredentials: true` 적용
- [ ] access token을 localStorage에 저장하지 않음
- [ ] 401 single-flight refresh interceptor 구현
- [ ] refresh 실패 시 `/admin/login` redirect
- [ ] ADMIN/EDITOR 권한별 버튼 제어
- [ ] `PUBLISHED/HIDDEN/REMOVED` enum 매핑 확인
- [ ] 제재·pipeline 실행 요청에 중복 클릭 방지
- [ ] requestId 기반 오류 추적
- [ ] token/password가 로그·analytics에 기록되지 않는지 확인
- [ ] production API origin과 CORS credentials 동작 확인
- [ ] HTTPS 환경에서 `__Host-` cookie가 저장되는지 확인

## 백엔드와 추가 확인할 계약

현재 구현은 Admin API 기반과 실제 저장소까지 연결된 상태다. FE 통합 직전에 다음 항목은 API contract test로 한 번 더 고정하는 것이 좋다.

- 관리자 principal에 nickname을 포함할지 여부
- 신고 상태를 FE의 `PENDING`와 백엔드의 `OPEN` 중 어떤 canonical 값으로 통일할지
- pipeline 실행을 동기 full snapshot 실행으로 둘지 비동기 job으로 전환할지
- 관리자 audit log 응답을 화면에 노출할지 여부
