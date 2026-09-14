# Odii API Reference Snapshot

이 디렉터리는 Issue #61의 Odii API qualification 결과를 기록한다. 실제 응답 fixture는 `testing/fixtures/provider/odii`에 있고, 이 디렉터리의 `manifest.json`은 각 fixture의 SHA-256, redacted request URL, capture 시각을 고정한다.

## 출처와 이용 조건

- 공식 데이터: 한국관광공사_관광지 오디오 가이드정보_GW
- 공식 페이지: https://www.data.go.kr/data/15101971/openapi.do
- 제공기관: 한국관광공사
- 비용: 무료
- 이용허락범위: 제한 없음
- 심의: 개발단계 자동승인, 운영단계 심의승인
- 신청 가능 트래픽: 개발계정 1,000건, 운영계정은 활용사례 등록 후 증설 신청 가능
- 공식 설명의 언어 범위: 한국어, 영어, 중국어, 일본어

위 조건은 2026-09-14 캡처 시점의 data.go.kr 상세 페이지를 근거로 한다. 운영 공개 전에는 운영계정 심의와 실제 운영 quota를 다시 확인한다.

## 캡처 범위

| scenario | endpoint | 목적 |
|---|---|---|
| `story_based_first_page` | `storyBasedList` | spot `tid=30/tlid=102`의 story 목록 1페이지, 공식 대본과 음원 URL |
| `story_based_second_page` | `storyBasedList` | 같은 spot의 2페이지, `pageNo/numOfRows/totalCount` 계약 |
| `story_location_based` | `storyLocationBasedList` | 좌표·반경 기반 story 검색 |
| `story_search_hanok` | `storySearchList` | 한국어 키워드 검색과 한옥 관련 story |
| `story_search_hanok_en` | `storySearchList` | 영어 `langCode=en` 검색과 언어별 `tlid/stlid` 차이 |
| `empty_search` | `storySearchList` | 결과 없음. `items`가 빈 문자열이고 `totalCount=0` |
| `empty_script_story` | `storySearchList` | `script=""`와 `audioUrl=""`가 함께 나타나는 story fixture |
| `provider_error_missing_key` | `storySearchList` | `serviceKey` 누락 시 인증 오류 envelope |

## 관찰된 응답 계약

- 성공 응답은 `response.header.resultCode="0000"`와 `response.header.resultMsg="OK"`를 사용한다.
- 정상 목록은 `response.body.items.item` 배열을 사용한다.
- 결과 없음은 `response.body.items`가 객체가 아니라 빈 문자열이다.
- provider 인증 오류는 성공 envelope와 다르게 `OpenAPI_ServiceResponse.cmmMsgHeader` 형태로 반환될 수 있다.
- `langCode`는 필수 요청 파라미터다. 누락하면 provider가 `NO_MANDATORY_REQUEST_PARAMETERS_ERROR1(langCode)`를 반환한다.
- pagination 필드는 원천 기준 `numOfRows`, `pageNo`, `totalCount`다. OnMaru public API cursor와 직접 공유하지 않는다.

## 필드 매핑 기준

| provider field | OnMaru 후보 의미 | 비고 |
|---|---|---|
| `tid` | Odii spot source id | `tlid`와 함께 spot 언어 identity 구성 |
| `tlid` | Odii spot language id | `langCode`별로 다를 수 있음 |
| `stid` | Odii story source id | `stlid`와 함께 story 언어 identity 구성 |
| `stlid` | Odii story language id | 같은 `stid`도 언어별 `stlid`가 다름 |
| `title` | story 또는 spot 표시 제목 | endpoint별 제목 범위는 adapter에서 유지 |
| `audioTitle` | story audio title | public DTO 노출 여부는 A03에서 결정 |
| `script` | provider official transcript | 빈 문자열 가능. OnMaru estimated transcript와 구분 |
| `audioUrl` | provider audio content URL | 빈 문자열 가능. public URL 정책은 A03에서 별도 결정 |
| `playTime` | duration seconds 후보 | 문자열로 오며 정수 변환 검증 필요 |
| `mapX`, `mapY` | longitude, latitude | 문자열 decimal |
| `langCode` | provider language code | 캡처됨: `ko`, `en` |
| `imageUrl` | provider image URL | 빈 문자열 가능 |
| `createdtime`, `modifiedtime` | provider timestamp | `yyyyMMddHHmmss` 문자열 |

## 대본과 음원 권리 처리

`script`는 provider가 응답한 official transcript로 기록한다. OnMaru가 생성하거나 보정한 estimated transcript는 이 fixture에 없다. `empty_script_story` fixture처럼 official transcript가 빈 문자열인 story가 존재하므로, 후속 adapter는 `script` nullable/empty를 허용하고 transcript provenance를 `OFFICIAL`, `MISSING`, `ESTIMATED` 같은 별도 상태로 분리해야 한다.

`audioUrl`도 빈 문자열일 수 있다. 음원 URL이 있는 경우에도 provider content URL을 그대로 브라우저에 노출할지는 이 qualification의 결정 범위가 아니다. A03 공개 API 구현에서 secret URL 여부, cache/proxy, attribution 표시 정책을 다시 검토한다.

## Redaction 정책

- `serviceKey` query parameter는 fixture와 manifest의 request URL에서 제거한다.
- `.env.local` 값은 repository에 저장하지 않는다.
- `node scripts/validate-odii-fixtures.mjs`는 fixture hash, 필수 scenario, JSON parse, secret-like 문자열을 검증한다.

## 검증 명령

```bash
node --test scripts/test/odii-fixture-validation.test.mjs
node scripts/validate-odii-fixtures.mjs
```
