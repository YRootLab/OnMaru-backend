# 한옥 수결첩 API FE 인계서

기준일: 2026-09-26  
관련 작업: GitHub Issue #262  
API 계약 버전: `1.2`

## 한 문장으로 이해하기

수결첩은 이제 브라우저 `localStorage`에 찍는 데모 도장이 아니라, **로그인한 사용자가 실제 한옥 장소 가까이에서 위치를 확인받아 서버에 영구 저장하는 방문 기록**입니다. 수결 디자인 목록은 누구나 볼 수 있지만, 내 획득 상태 조회와 체크인은 로그인한 사용자만 가능합니다.

서버는 체크인 순간에만 위도·경도를 받아 장소와의 거리를 계산합니다. 위도·경도 원문은 저장하지 않고 응답에도 돌려주지 않습니다. 저장되는 정보는 장소, 체크인 시각, 계산 거리, 당시 위치 정확도처럼 판정에 필요한 최소 정보입니다.

## FE가 연결할 API 세 개

| 용도 | Method / path | 로그인 | 중요한 응답 |
|---|---|---:|---|
| 수결 디자인·조건 목록 | `GET /api/v1/stamps` | 불필요 | `stamps` 12개, 개인 획득 여부 없음 |
| 내 수결첩 | `GET /api/v1/me/stamp-book` | 필요 | `summary`, 획득 여부가 포함된 `stamps` |
| 현장 체크인 | `POST /api/v1/places/{placeId}/check-ins` | 필요 | 체크인 결과, 새 수결, 갱신된 요약 |

공개 랭킹 API는 이번 범위에 없습니다. 공개 닉네임과 랭킹 참여 동의 정책이 없기 때문에 현재 `/stamps` 화면의 하드코딩 랭킹은 숨기거나 “데모”라고 명확히 표시해야 합니다.

## 화면 흐름

1. 화면 진입 시 `GET /api/v1/stamps`로 수결 설명과 이미지를 구성합니다.
2. 로그인 상태라면 `GET /api/v1/me/stamp-book`을 추가 호출해 `collected` 상태를 덮어씁니다.
3. 비로그인 상태라면 수결은 잠금 상태로 보여 주고 체크인 버튼에서 로그인 안내를 표시합니다.
4. 사용자가 체크인 버튼을 누르면 브라우저 위치 권한을 요청합니다.
5. 위치 확보 후 CSRF token과 새 UUID 멱등성 키를 준비해 체크인 API를 호출합니다.
6. 201이면 새 체크인 애니메이션과 `newAwards`를 보여 줍니다. 200이면 이미 같은 15분 구간에 처리된 방문이므로 중복 성공 안내만 보여 줍니다.
7. 응답의 `summary`를 즉시 반영하고, 필요하면 내 수결첩을 다시 조회합니다.

## 복사해서 시작할 수 있는 TypeScript 예제

아래 코드는 브라우저에서 동일 origin API를 호출한다고 가정합니다. 쿠키 인증을 위해 모든 개인 API에 `credentials: 'include'`를 유지해야 합니다.

```ts
type ApiError = {
  schemaVersion: '1.2';
  code: string;
  message: string;
  requestId: string;
  details: Record<string, unknown>;
};

type StampSummary = {
  collectedCount: number;
  totalCount: number;
  visitedRegionCount: number;
  requiredRegionCount: number;
  completionRate: number;
};

type CheckInResponse = {
  schemaVersion: '1.2';
  checkIn: {
    id: string;
    placeId: string;
    checkedInAt: string;
    distanceMeters: number;
    alreadyCheckedIn: boolean;
  };
  newAwards: Array<{
    code: string;
    name: string;
    sealText: string;
    rarity: 'COMMON' | 'REGIONAL' | 'RARE' | 'LEGENDARY';
    collectedAt: string;
  }>;
  summary: StampSummary;
};

async function getCsrfToken(): Promise<string> {
  const response = await fetch('/api/v1/auth/csrf', {
    credentials: 'include',
    cache: 'no-store',
  });
  if (!response.ok) throw await response.json() as ApiError;
  const body = await response.json() as { token: string };
  return body.token;
}

function currentPosition(): Promise<GeolocationPosition> {
  return new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(resolve, reject, {
      enableHighAccuracy: true,
      timeout: 10_000,
      maximumAge: 0,
    });
  });
}

export async function checkInHanok(placeId: string): Promise<CheckInResponse> {
  if (!navigator.geolocation) {
    throw new Error('이 브라우저는 위치 확인을 지원하지 않습니다.');
  }

  const [position, csrfToken] = await Promise.all([currentPosition(), getCsrfToken()]);
  // 한 번의 사용자 클릭에 하나만 만들고, 네트워크 재시도 때는 같은 값을 재사용합니다.
  const idempotencyKey = crypto.randomUUID();
  const payload = {
    latitude: position.coords.latitude,
    longitude: position.coords.longitude,
    accuracyMeters: position.coords.accuracy,
  };
  const send = () => fetch(`/api/v1/places/${encodeURIComponent(placeId)}/check-ins`, {
    method: 'POST',
    credentials: 'include',
    cache: 'no-store',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrfToken,
      'Idempotency-Key': idempotencyKey,
    },
    body: JSON.stringify(payload),
  });

  let response = await send();
  if (response.status >= 500) response = await send(); // 같은 key와 body로 한 번만 재시도
  if (!response.ok) throw await response.json() as ApiError;
  return response.json() as Promise<CheckInResponse>;
}
```

수결 목록과 개인 수결첩은 다음처럼 조회할 수 있습니다.

```ts
const catalog = await fetch('/api/v1/stamps').then(response => response.json());

const myBookResponse = await fetch('/api/v1/me/stamp-book', {
  credentials: 'include',
  cache: 'no-store',
});
if (myBookResponse.status === 401) {
  // 비로그인 화면으로 전환
}
const myBook = await myBookResponse.json();
```

## 성공 응답을 화면에 반영하는 법

새 방문은 HTTP 201이며 `alreadyCheckedIn=false`입니다. `newAwards`가 두 개일 수도 있습니다. 예를 들어 저녁에 북촌을 처음 방문하면 지역 수결과 야간 수결이 함께 생길 수 있으므로 첫 항목만 표시하지 말고 배열 전체를 순서대로 보여 주세요.

같은 회원이 같은 장소를 같은 UTC 15분 구간에 다시 체크인하면 HTTP 200, `alreadyCheckedIn=true`, `newAwards=[]`입니다. 이 경우 오류 토스트가 아니라 “이미 이번 방문이 기록되었어요” 같은 성공성 안내가 적합합니다.

`completionRate`는 서버가 계산한 0~100 정수입니다. FE에서 다시 계산하지 말고 그대로 사용합니다. 시각은 UTC ISO-8601 문자열이므로 사용자 지역 시간으로 포맷해서 표시합니다.

## 오류별 사용자 안내

FE는 영문 `message`가 아니라 안정적인 `code`로 문구를 선택합니다. 문의나 장애 화면에는 `requestId`를 함께 보여 주면 서버 추적이 쉬워집니다.

| HTTP / code | 사용자에게 보여 줄 의미 | 권장 동작 |
|---|---|---|
| 400 `VALIDATION_ERROR` | 위치 값 또는 요청 형식이 잘못됨 | 위치를 새로 얻어 다시 시도. 멱등성 키 오류면 UUID 생성 로직 점검 |
| 401 `AUTH_REQUIRED` | 로그인 필요 | 로그인 후 사용자가 눌렀던 체크인 의도를 다시 확인시켜 실행 |
| 403 `CSRF_INVALID` | 보안 토큰 만료·불일치 | CSRF token을 한 번 새로 받은 뒤 재시도 |
| 404 `NOT_FOUND` | 공개 중인 체크인 가능 한옥 장소가 아님 | 장소 정보 새로고침, 계속되면 체크인 버튼 숨김 |
| 409 `IDEMPOTENCY_CONFLICT` | 같은 키를 다른 요청에 재사용함 | 자동으로 새 키를 만들어 성공처럼 처리하지 말고 클라이언트 버그로 기록 |
| 422 `LOCATION_ACCURACY_TOO_LOW` | GPS 오차가 100m보다 큼 | 야외로 이동하거나 위치 정확도가 좋아진 뒤 새 위치로 재시도 |
| 422 `OUTSIDE_CHECK_IN_RADIUS` | 오차를 고려해도 장소에서 200m 밖임 | 장소 가까이 이동하도록 안내 |
| 429 `CHECK_IN_RATE_LIMITED` | KST 기준 하루 30회 초과 | `details.retryAfterSeconds` 또는 `Retry-After` 뒤 재시도 가능 시각 표시 |
| 503 `SERVICE_UNAVAILABLE` | Catalog 또는 DB 일시 장애 | 현재 화면 유지, 짧은 지수 backoff 후 사용자가 재시도하도록 제공 |

브라우저 자체 위치 오류도 별도로 처리합니다. `PERMISSION_DENIED`는 브라우저·OS 설정 안내, `POSITION_UNAVAILABLE`은 위치 서비스 켜기 안내, `TIMEOUT`은 다시 시도 버튼이 적합합니다. 이 세 오류는 서버에서 온 `ApiError`가 아닙니다.

## 기존 localStorage에서 서버 기준으로 전환하기

현재 키 `onmaru_hanok_stamps_v1`의 값은 실제 위치 검증 없이 생성된 데모 데이터이므로 서버로 업로드하거나 획득 기록으로 변환하면 안 됩니다.

1. 새 FE 배포부터 렌더링의 정답을 `GET /api/v1/me/stamp-book`으로 바꿉니다.
2. 로그인 회원의 수결첩 API가 성공한 뒤에만 `localStorage.removeItem('onmaru_hanok_stamps_v1')`를 실행합니다.
3. API 실패 때 과거 localStorage 도장을 실제 획득 상태처럼 대신 표시하지 않습니다. skeleton, 재시도, 오류 안내를 사용합니다.
4. 비로그인 사용자의 localStorage 값도 획득 상태로 사용하지 않습니다. 공개 카탈로그와 로그인 안내만 표시합니다.
5. 기존 하드코딩 랭킹은 숨기거나 데모 표기를 유지합니다. 서버 데이터로 임의 랭킹을 계산하거나 회원 정보를 노출하지 않습니다.

```ts
const LEGACY_STAMP_KEY = 'onmaru_hanok_stamps_v1';

async function loadServerStampBook() {
  const response = await fetch('/api/v1/me/stamp-book', {
    credentials: 'include',
    cache: 'no-store',
  });
  if (!response.ok) return { response, book: null };
  const book = await response.json();
  localStorage.removeItem(LEGACY_STAMP_KEY); // 서버 조회 성공 뒤에만 제거
  return { response, book };
}
```

## QA 체크리스트

- 비로그인에서도 수결 디자인 12개가 보이고, 개인 획득처럼 표시되지는 않는다.
- 비로그인 체크인은 로그인 안내로 이어진다.
- 위치 권한 거부·시간 초과·100m 초과 오차가 서로 다른 안내로 보인다.
- 새 체크인은 201과 함께 모든 `newAwards`를 표시한다.
- 같은 요청의 네트워크 재시도는 같은 UUID 키와 같은 body를 사용한다.
- 같은 장소를 다시 눌러 200을 받아도 오류로 표시하지 않는다.
- 응답·analytics·client log에 위도, 경도, 위치 정확도를 남기지 않는다.
- 개인 수결첩과 체크인 응답을 service worker나 CDN cache에 저장하지 않는다.
- 서버 수결첩 조회 성공 후 legacy localStorage 키가 제거된다.
- 랭킹 탭이 숨겨지거나 데모임이 명확하다.

정확한 필드 제약은 [`hanok-stamps.openapi.yaml`](../contracts/openapi/hanok-stamps.openapi.yaml), 설계 근거와 개인정보 결정은 [`2026-09-26-hanok-stamp-book-design.md`](../superpowers/specs/2026-09-26-hanok-stamp-book-design.md)를 참고합니다.
