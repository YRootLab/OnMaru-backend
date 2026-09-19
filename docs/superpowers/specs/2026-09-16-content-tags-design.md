# Content Tags Design

## 목표

Issue #208은 외부 제공기관이 주는 분류 태그가 아니라 OnMaru가 관광지 상세 설명과 Odii 오디오 대본에서 자체 생성한 `contentTags`를 FE에 제공한다. 태그는 FE가 해시태그 칩처럼 렌더링할 수 있는 콘텐츠 이해 보조 신호이며, BE 응답에는 `#` 없이 문자열 배열로 제공한다.

## 범위

- `GET /api/v1/places/{placeId}` 응답에 `contentTags`를 추가한다.
- `GET /api/v1/hanoks/{placeId}` 응답에 `contentTags`를 추가한다.
- `GET /api/v1/odii/stories`의 각 story summary와 `GET /api/v1/odii/stories/{storyId}`의 nested story summary에 `contentTags`를 추가한다.
- 목록 한옥 카드의 기존 `tags`는 이번 변경에서 이름을 바꾸지 않는다.

## 추출 방식

LLM을 사용하지 않는다. 구현은 TextRank 계열의 co-occurrence 기반 점수와 관광 도메인 사전/불용어 보정을 함께 사용한다.

- 후보는 한국어/영문/숫자 토큰을 정규화한 뒤 1~2 gram 구문으로 만든다.
- stopword, 너무 짧은 토큰, 너무 일반적인 관광 단어는 제거한다.
- 같은 후보가 여러 번 등장하거나 제목/하이라이트/대본 초반부에 등장하면 점수를 더 받는다.
- 관광 도메인 사전에 있는 구문은 보정 점수를 받는다.
- 최종 결과는 입력 순서가 아니라 점수순이며, 같은 점수는 최초 등장 순서로 안정 정렬한다.
- 최대 7개, 각 태그는 24자 이하로 제한한다.

## 데이터 흐름

MVP에서는 query model이 가진 설명/하이라이트/대본에서 응답 생성 시 결정적으로 계산한다. 이후 실제 DB sync/publish 구현이 붙으면 같은 extractor를 revision publish 단계로 이동하고, projection에는 저장된 `contentTags`만 노출한다.

장소/한옥 입력:

- `name`
- `category`
- `description`
- `highlights`

Odii 입력:

- `title`
- `audioTitle`
- `category`
- `transcript` lines

## API 계약

`contentTags`는 항상 배열이다. 원문이 부족하거나 추출 후보가 없으면 빈 배열을 내려준다. FE는 각 항목 앞에 `#`을 붙여 표시할 수 있지만 저장/요청 값에는 `#`을 포함하지 않는다.

예시:

```json
{
  "contentTags": ["한옥 골목", "공예 체험", "야간 산책"]
}
```

## 검증

- extractor 단위 테스트로 도메인 구문 우선순위, stopword 제거, 7개 제한을 검증한다.
- 장소/한옥 query service 테스트로 상세 응답의 `contentTags`를 검증한다.
- Odii query service 테스트로 목록/상세 story summary의 `contentTags`를 검증한다.
- OpenAPI와 fixture에 `contentTags`를 반영한다.
- FE 전달 문서에 필드 의미와 렌더링 규칙을 기록한다.
