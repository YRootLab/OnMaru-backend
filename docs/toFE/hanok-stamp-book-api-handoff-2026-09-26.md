# 한옥 수결첩 API FE 인계서

기준일: 2026-09-27
관련 작업: GitHub Issue #262  
API 계약 버전: `1.3`

## 한 문장으로 이해하기

수결첩은 이제 브라우저 `localStorage`에 찍는 데모 도장이 아니라, **로그인한 사용자가 실제 한옥 장소 가까이에서 위치를 확인받아 서버에 영구 저장하는 방문 기록**입니다. 수결 디자인 목록은 누구나 볼 수 있지만, 내 획득 상태 조회와 체크인은 로그인한 사용자만 가능합니다.

공개 랭킹은 별도로 동의한 회원만 참여합니다. 수결을 모았다는 이유만으로 자동 등록되지 않습니다. 참여하면 서버가 만든 익명 별명과 공개용 UUID가 표시되고, 철회하면 공개 목록에서 빠집니다. 이 버전은 사용자 지정 별명을 받지 않습니다.

서버는 체크인 순간에만 위도·경도를 받아 장소와의 거리를 계산합니다. 위도·경도 원문은 저장하지 않고 응답에도 돌려주지 않습니다. 저장되는 정보는 장소, 체크인 시각, 계산 거리, 당시 위치 정확도처럼 판정에 필요한 최소 정보입니다.

## FE가 연결할 수결첩 API 세 개

| 용도 | Method / path | 로그인 | 중요한 응답 |
|---|---|---:|---|
| 수결 디자인·조건 목록 | `GET /api/v1/stamps` | 불필요 | `stamps` 12개, 개인 획득 여부 없음 |
| 내 수결첩 | `GET /api/v1/me/stamp-book` | 필요 | `summary`, 획득 여부가 포함된 `stamps` |
| 현장 체크인 | `POST /api/v1/places/{placeId}/check-ins` | 필요 | 체크인 결과, 새 수결, 갱신된 요약 |

공개 랭킹에는 아래의 별도 API 세 개를 사용합니다. 기존 하드코딩 순위를 서버 순위와 섞지 않습니다.

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
  schemaVersion: '1.3';
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
  schemaVersion: '1.3';
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
| 400 `VALIDATION_ERROR` | 위치 값 또는 요청 형식이 잘못됨 | 위치를 새로 얻어 다시 시도 |
| 400 `IDEMPOTENCY_KEY_MISSING` / `IDEMPOTENCY_KEY_INVALID` | 멱등성 키가 없거나 UUID가 아님 | `crypto.randomUUID()` 생성·전달 로직 점검 |
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
5. 기존 하드코딩 랭킹 대신 아래 공개 랭킹 API 응답을 사용합니다. 서버 데이터로 임의 랭킹을 계산하거나 회원 정보를 노출하지 않습니다.

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

## 익명 공개 랭킹 연동

수결 획득과 랭킹 공개 동의는 별개입니다. 회원이 직접 참여 설정을 켠 뒤에만 공개 순위에 들어갑니다. 비동의·철회·비활성 회원은 수결이 많아도 목록에 나오지 않습니다. 참여 시 별명은 서버가 생성하며 `nicknameType`은 `GENERATED`입니다. 사용자 지정 별명 입력란이나 별명 변경 API는 이 버전에 없습니다.

| 용도 | Method / path | 인증 | 성공 응답 |
|---|---|---|---|
| 공개 목록 | `GET /api/v1/stamps/leaderboard?limit=20` | 불필요 | `StampLeaderboardResponse`, 200 |
| 내 참여 상태·순위 | `GET /api/v1/me/stamp-ranking` | 회원 session cookie | `StampRankingStatusResponse`, 200 |
| 참여 또는 철회 | `PUT /api/v1/me/stamp-ranking` | 회원 session cookie + CSRF | 변경 후 `StampRankingStatusResponse`, 200 |

공개 목록 `limit`은 생략하면 20, 허용 범위는 1~100입니다. 목록이 비어 있으면 `entries: []`입니다. `generatedAt`은 서버가 목록을 만든 UTC 시각입니다. 개인 조회는 아직 참여하지 않았어도 200을 반환하므로 `participating`으로 상태를 판단하세요. `participantCount`는 공개 참여 중인 활성 회원 수이며, 목록의 `limit`과 다릅니다.

다음 TypeScript 형식은 [OpenAPI 계약](../contracts/openapi/hanok-stamps.openapi.yaml)의 필드를 그대로 옮겼습니다. 날짜는 ISO-8601 문자열, `publicId`는 UUID 문자열입니다. `rank`는 공동 순위가 아닌 1부터 시작하는 정수입니다.

```ts
type RankingEntry = {
  rank: number;
  publicId: string;
  nickname: string;
  nicknameType: 'GENERATED';
  stampCount: number;
  visitedRegionCount: number;
  completionRate: number;
};

type StampLeaderboardResponse = {
  schemaVersion: '1.3';
  generatedAt: string;
  entries: RankingEntry[];
};

type StampRankingStatusResponse = {
  schemaVersion: '1.3';
  participating: boolean;
  publicNickname: string | null;
  nicknameType: 'GENERATED' | null;
  rank: number | null;
  participantCount: number;
  stampCount: number;
  visitedRegionCount: number;
  completionRate: number;
};

type StampRankingUpdateRequest = { participating: boolean };
```

예를 들어 공개 항목은 `{"rank":1,"publicId":"550e8400-e29b-41d4-a716-446655440000","nickname":"고즈넉한여행자-A7K2","nicknameType":"GENERATED","stampCount":12,"visitedRegionCount":7,"completionRate":100}`입니다. 미참여 개인 응답은 `{"schemaVersion":"1.3","participating":false,"publicNickname":null,"nicknameType":null,"rank":null,"participantCount":143,"stampCount":8,"visitedRegionCount":5,"completionRate":66}`처럼 개인 진행률은 유지하고 공개 별명과 순위만 비웁니다.

브라우저에서는 다음 함수를 사용할 수 있습니다. 로그인 세션과 CSRF cookie는 브라우저가 `credentials: 'include'`로 전달합니다. CSRF cookie는 `HttpOnly`이므로 JavaScript에서 읽지 말고 `/api/v1/auth/csrf`의 JSON `token`을 헤더에 넣습니다. 이 예제는 API와 같은 origin에서 실행하는 HTTPS 페이지 기준입니다.

```ts
async function rankingJson<T>(response: Response): Promise<T> {
  if (!response.ok) throw await response.json() as ApiError;
  return response.json() as Promise<T>;
}

export async function getLeaderboard(limit = 20): Promise<StampLeaderboardResponse> {
  const response = await fetch(`/api/v1/stamps/leaderboard?limit=${limit}`, {
    credentials: 'include', cache: 'no-store',
  });
  return rankingJson<StampLeaderboardResponse>(response);
}

export async function getMyRanking(): Promise<StampRankingStatusResponse> {
  const response = await fetch('/api/v1/me/stamp-ranking', {
    credentials: 'include', cache: 'no-store',
  });
  return rankingJson<StampRankingStatusResponse>(response);
}

export async function setRankingParticipation(participating: boolean): Promise<StampRankingStatusResponse> {
  const token = await getCsrfToken(); // 위 체크인 예제와 같은 /api/v1/auth/csrf 호출
  const body: StampRankingUpdateRequest = { participating };
  const response = await fetch('/api/v1/me/stamp-ranking', {
    method: 'PUT',
    credentials: 'include',
    cache: 'no-store',
    headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': token },
    body: JSON.stringify(body),
  });
  if (response.status === 429) {
    const error = await response.json() as ApiError;
    const seconds = Number(response.headers.get('Retry-After'));
    // seconds가 유효하면 참여 버튼에 대기 시간을 표시하세요. 철회 버튼은 계속 사용 가능합니다.
    throw { ...error, retryAfterSeconds: seconds };
  }
  return rankingJson<StampRankingStatusResponse>(response);
}
```

터미널에서 재현할 때는 로그인 후 받은 회원 session cookie가 필요합니다. 아래 `cookies.txt`는 사용자의 테스트 세션이 저장된 파일을 뜻하며 공개 자료에 첨부하지 않습니다. CSRF 호출이 같은 cookie jar에 보안 cookie를 추가합니다.

```bash
curl -b cookies.txt -c cookies.txt 'https://YOUR_HOST/api/v1/stamps/leaderboard?limit=20'
curl -b cookies.txt -c cookies.txt 'https://YOUR_HOST/api/v1/me/stamp-ranking'
curl -b cookies.txt -c cookies.txt 'https://YOUR_HOST/api/v1/auth/csrf'
# 위 응답의 token 값을 아래에 넣습니다. 응답 headerName은 X-CSRF-TOKEN입니다.
curl -i -b cookies.txt -c cookies.txt -X PUT 'https://YOUR_HOST/api/v1/me/stamp-ranking' \
  -H 'Content-Type: application/json' -H 'X-CSRF-TOKEN: YOUR_CSRF_TOKEN' \
  --data '{"participating":true}'
# 철회할 때도 새 CSRF token을 받아 같은 PUT에 {"participating":false}를 보냅니다.
```

### 화면 상태와 갱신 시점

| 상태 | FE 처리 |
|---|---|
| 비로그인 공개 조회 | 목록은 표시하고, 참여 버튼은 로그인 안내로 연결합니다. 개인 조회의 401을 빈 참여 상태로 해석하지 않습니다. |
| 로그인·미참여 | 개인 `stampCount`·진행률과 익명 참여 안내를 표시합니다. `publicNickname`과 `rank`는 `null`입니다. |
| 로그인·참여 | 개인 `rank`, 서버 생성 `publicNickname`, 전체 `participantCount`를 표시하고 철회 버튼을 제공합니다. 목록 첫 20명 밖이어도 개인 순위는 값이 있을 수 있습니다. |
| 참여 요청 429 | `Retry-After` 초가 지난 뒤 참여를 다시 시도할 수 있게 안내합니다. `details.retryAfterSeconds`도 동일한 초 단위 값입니다. 철회는 대기 없이 호출할 수 있습니다. |
| 철회 성공 | PUT 응답의 `participating:false`, `publicNickname:null`, `nicknameType:null`, `rank:null`을 반영하고 공개 목록을 다시 조회합니다. |

공개·개인·변경 응답은 모두 `Cache-Control: no-store`입니다. FE도 `cache: 'no-store'`를 사용하고 service worker·CDN·localStorage에 목록이나 개인 상태를 저장하지 마세요. 철회 transaction이 commit된 뒤 시작한 다음 공개 요청부터 해당 항목이 보이지 않습니다. 진행 중이던 이전 요청의 화면 결과가 뒤늦게 도착할 수 있으므로, 철회 뒤 재조회 결과로 화면을 갱신하세요. 참여와 철회에 같은 PUT을 여러 번 보내면 현재 상태를 반환하며 별명을 불필요하게 다시 발급하지 않습니다. 철회 후 재참여하면 새 공개 ID와 새 별명이 발급됩니다.

순위는 점수 동률도 서로 다른 번호로 정합니다. ① 활성 수결 획득 수 많은 순, ② 서로 다른 활성 지역 수결 권역 수 많은 순, ③ 마지막 활성 수결 획득 시각 오래된 순(`null`은 뒤), ④ 공개 UUID 오름차순입니다. 전체 참여자를 먼저 정렬·번호 매긴 다음 `limit`만큼 보여 줍니다. 마지막 획득 시각은 정렬에만 사용하며 응답에는 없습니다. `completionRate`는 활성 수결 중 획득 비율을 내림한 정수로, FE에서 별도 계산하지 않습니다.

### 오류와 개인정보 경계

오류 body는 `ApiError`(`schemaVersion:'1.3'`, `code`, `message`, `requestId`, `details`)입니다. 화면 문구는 `message` 대신 `code`로 결정합니다.

| HTTP / code | 발생 조건 | FE 처리 |
|---|---|---|
| 400 `VALIDATION_ERROR` | `limit` 범위·형식 오류, PUT body 누락·잘못된 `participating` 형식·알 수 없는 필드 | 요청 구성 확인. 같은 잘못된 body 자동 재전송 금지 |
| 401 `AUTH_REQUIRED` | 개인 GET/PUT에 유효한 회원 세션 없음 | 로그인 안내 후 개인 상태 재조회 |
| 403 `CSRF_INVALID` | PUT의 CSRF cookie/header 불일치 또는 origin 오류 | 같은 origin인지 확인, `/auth/csrf`에서 새 token을 받아 한 번 재시도 |
| 429 `RATE_LIMITED` | 참여 시작·재참여가 마지막 실제 변경 후 5초 이내 | `Retry-After` 뒤 참여 재시도. 철회는 즉시 가능 |
| 503 `SERVICE_UNAVAILABLE` | DB 장애 또는 익명 값 생성 충돌 5회 소진 | 기존 화면을 유지하고 나중에 재시도 안내. 성공으로 표시하지 않음 |

공개 응답에는 내부 회원 ID·UUID, OAuth 이름·이메일·프로필, 전화번호, 위치·장소·체크인 이력과 시각, 마지막 수결 획득 시각, `consentedAt`·`withdrawnAt`이 없습니다. `publicId`는 랭킹 전용 무작위 UUID이며 회원 ID가 아닙니다. 개인 응답에도 위치·장소·체크인 내역은 없습니다. 분석 이벤트와 오류 로그에도 별명과 공개 ID를 담지 마세요.

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
- 랭킹 탭이 공개 API 응답만 표시하고, 미참여 회원을 자동으로 등록하지 않는다.
- 철회 성공 후 새 목록에서 해당 익명 항목이 사라지고, 개인 별명·순위도 비워진다.
- 429 동안 참여만 대기하며 철회는 계속 가능하다.

정확한 필드 제약은 [`hanok-stamps.openapi.yaml`](../contracts/openapi/hanok-stamps.openapi.yaml), 원래 수결첩 설계는 [`2026-09-26-hanok-stamp-book-design.md`](../superpowers/specs/2026-09-26-hanok-stamp-book-design.md), 후속 랭킹 결정은 [`2026-09-27-hanok-stamp-ranking-design.md`](../superpowers/specs/2026-09-27-hanok-stamp-ranking-design.md)를 참고합니다.
