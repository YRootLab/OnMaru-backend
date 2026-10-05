# 정보지도 카테고리 요청 계약 변경

## 결정과 범위

정보지도에서 `전체` 선택지를 제거하고 프론트엔드의 첫 선택을 `한옥`으로 한다. 백엔드는 기본 카테고리를 정하지 않는다. 정보지도 장소 목록과 viewport 요청은 모두 유효한 `category`를 명시해야 한다. 관련 작업은 백엔드 Issue [#646](https://github.com/YRootLab/OnMaru-backend/issues/646)과 프론트엔드 Issue [#334](https://github.com/YRootLab/OnMaru-Frontend/issues/334)에서 추적한다.

이 변경은 정보지도에만 적용한다. 온기모드의 `전체 온기`, 후기의 `ALL` 스코프 등 다른 도메인의 전체 선택은 유지한다. viewport의 최대 60개 표시 항목, zoom별 PLACE/CLUSTER/DISTRICT/REGION 전환, 장소 목록의 페이지 제한도 유지한다.

## 요청과 응답

| 호출 | 카테고리 계약 | 누락·빈 값·`ALL` | 유효 카테고리 |
| --- | --- | --- | --- |
| `GET /api/v1/map/info/viewport` | 필수 query parameter | HTTP 400 `INVALID_REQUEST`, `details.field=category` | 기존 viewport 응답 |
| `GET /api/v1/map/info/places` | 필수 query parameter | HTTP 400 `INVALID_REQUEST`, `details.field=category` | 기존 cursor 목록 응답 |

허용 값은 `HANOK`, `SPOT`, `EXPERIENCE`, `CULTURE`, `FESTIVAL`, `STAY`, `FOOD`, `CAFE`, `MARKET`다. 대소문자 정규화는 기존 호출 방식을 따른다. OpenAPI의 공통 Category parameter에서 `ALL`과 기본값을 제거하고, 두 operation의 필수 parameter로 선언한다. 오류 응답은 기존 형식을 사용한다.

프론트엔드는 정보모드 첫 진입과 모드 재진입 시 `hanok`을 선택하고 viewport 및 목록 요청에 `HANOK`을 명시한다. URL에 유효한 다른 카테고리가 있으면 그 값을 사용한다. `category=all`, 빈 값, 알 수 없는 값은 `hanok`으로 정규화한 다음 요청한다. 정보모드의 `전체` 칩을 제거하고 `all`에 종속된 정보모드 표시 분기를 한옥 기본 화면에 맞춘다. 온기모드의 카테고리 UI와 데이터 흐름은 유지한다.

## 적용 순서

먼저 프론트엔드가 모든 정보지도 호출에 허용 카테고리를 명시하도록 배포한다. 그다음 백엔드에서 누락·`ALL` 거부를 활성화한다. 역순 배포 시 기존 프론트엔드가 초기 `ALL` 요청으로 400을 받을 수 있다. FE/BE PR의 검증과 릴리즈 설명에는 이 순서를 명시한다.

## 검증

- 두 백엔드 endpoint에서 카테고리 누락, 빈 값, `ALL`이 동일한 `400 INVALID_REQUEST`와 `details.field=category`를 반환한다.
- `HANOK`과 다른 허용 카테고리의 성공 응답, 기존 cursor와 viewport 집계 동작이 유지된다.
- FE 첫 진입, 모드 재진입, URL `category=all`에서 `HANOK` 요청이 발생하고 정보모드 `전체` 선택지가 표시되지 않는다.
- 온기모드 `전체 온기`는 그대로 동작한다.
- OpenAPI·계약 fixture, BE CI `verify`, FE 타입 검사와 지도 관련 테스트를 통과한다.

## 이후 검토

60개 제한 변경은 실제 화면별 항목 수, 응답 시간, 지도 렌더링 밀도를 측정한 후 별도 Issue에서 결정한다. `ALL` 거부는 전체 범위 조회를 막지만 반복 요청 자체를 막지는 않으므로 트래픽 남용 방어는 별도로 다룬다.
