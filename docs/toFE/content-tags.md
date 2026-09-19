# FE Content Tags 전달 명세

## 목적

`contentTags`는 한국관광공사나 Odii 제공기관이 주는 태그가 아니다. OnMaru BE가 관광지 상세 설명, 한옥 하이라이트, Odii 오디오 제목과 대본/자막에서 LLM 없이 추출한 콘텐츠 이해용 태그다.

FE는 이 값을 해시태그 칩처럼 보여줄 수 있다. API 값에는 `#`이 포함되지 않으므로 화면 렌더링에서만 `#전통문화`처럼 표시한다.

## 어떻게 동작하나

BE는 공개 projection의 텍스트를 모아 `contentTags`를 계산한다. Odii는 sync mapping 단계에서 story version에 태그를 싣고, 공개 API는 저장된 태그를 우선 사용한다. 장소·한옥은 현재 DB-backed publish adapter가 아직 없어서 projection에 저장된 태그가 있으면 그것을 우선 사용하고, 없으면 같은 pipeline으로 응답 시점에 deterministic fallback 계산을 한다.

장소·한옥 상세는 다음 입력을 사용한다.

- 장소명
- 카테고리
- 상세 설명
- 한옥 하이라이트

Odii는 다음 입력을 사용한다.

- 이야기 제목
- 오디오 제목
- 카테고리
- 대본/자막 라인

추출 pipeline은 LLM을 호출하지 않는다. 텍스트를 정규화한 뒤 1~2개 단어 구문 후보를 만들고, 반복 등장, 앞쪽 등장, 관광 도메인 구문 일치 여부를 점수화한다. 이후 자동 품질 필터가 너무 일반적인 단어와 filler phrase를 제거하고 점수가 높은 순서로 최대 7개를 내려준다.

운영자 `PIN`/`HIDE` override는 기본 운영 방식이 아니다. 정상 콘텐츠는 자동 pipeline으로 처리하고, override는 금칙어, 명백한 오탐, 캠페인성 대표 태그처럼 예외적인 경우에만 적용하는 안전장치다.

불용어, 일반어, 도메인 phrase는 BE resource 파일로 분리되어 있다. 따라서 `추천`, `관광`, `소개` 같은 일반어 제거와 `전통 정원`, `한옥 골목` 같은 도메인 phrase 보정은 코드 변경 없이 lexicon 검토로 조정할 수 있다.

예를 들어 설명과 대본에 `한옥 골목`, `공예 체험`, `야간 산책`이 반복되면 다음처럼 내려올 수 있다.

```json
{
  "contentTags": ["한옥 골목", "공예 체험", "야간 산책"]
}
```

## 응답 필드

| API | 위치 | 타입 |
|---|---|---|
| `GET /api/v1/places/{placeId}` | `body.contentTags` | `string[]` |
| `GET /api/v1/hanoks/{placeId}` | `body.contentTags` | `string[]` |
| `GET /api/v1/odii/stories` | `body.items[].contentTags` | `string[]` |
| `GET /api/v1/odii/stories/{storyId}` | `body.story.contentTags` | `string[]` |

## 기대효과

사용자는 긴 설명문이나 오디오를 재생하기 전에 콘텐츠의 핵심 주제를 빠르게 파악할 수 있다.

한옥 상세에서는 “이 장소가 무엇을 경험하게 해주는지”를 짧게 보여준다. 예를 들어 `#한옥 골목`, `#공예 체험`, `#야간 산책`은 단순 카테고리 `한옥`보다 훨씬 구체적인 탐색 단서다.

Odii에서는 재생 전 탐색성이 좋아진다. 제목만으로는 알기 어려운 오디오 내용을 `#궁궐`, `#왕실 문화`, `#처마`, `#담장`처럼 미리 보여줄 수 있다.

FE 입장에서는 별도 자연어 처리 없이 같은 API 응답만으로 상세 헤더, 카드 보조 정보, 오디오 목록의 미리보기 칩을 구성할 수 있다.

## 언제 업데이트되나

Odii는 source sync가 새 story version을 만들 때 태그도 같이 계산된다. 따라서 Odii 원본 제목, 오디오 제목, 대본이 바뀌고 새 revision이 publish되면 다음 공개 revision부터 `contentTags`가 바뀐다.

장소·한옥은 현재 projection에 저장된 `contentTags`가 있으면 그 값을 그대로 내려준다. 저장된 값이 비어 있으면 기존처럼 응답 시점 fallback 계산을 수행한다. 실제 DB-backed catalog publish adapter가 붙으면 `catalog_place_content_tag_versions`에 revision별 태그를 저장하고, API는 저장된 projection 값을 내려주는 방식으로 완전히 전환할 수 있다.

DB에는 revision 단위 저장을 위한 테이블이 준비되어 있다.

- `catalog_place_content_tag_versions`: 장소/한옥 revision별 생성 태그
- `audio_story_content_tag_versions`: Odii story revision별 생성 태그
- `content_tag_overrides`: 운영자가 특정 태그를 고정하거나 숨기는 override

공개 FE API는 계속 `string[]`만 받는다. 점수, 알고리즘 버전, source hash, 품질 리포트, 운영자 override 같은 내부 정보는 공개 응답에 노출하지 않는다.

Odii sync 결과에는 내부 운영용 태그 품질 요약이 포함될 수 있다. 이 값은 FE 공개 API가 아니라 운영 관측성용이며, `contentTags` 화면 렌더링 계약에는 영향을 주지 않는다.

내부 품질 기준은 현재 `0개=EMPTY_RESULT`, `3개 미만=LOW_CONFIDENCE`, `자동 제거 일반어 5개 이상=GENERIC_HEAVY`다. 이 기준도 FE 표시 계약이 아니라 운영 관측성 기준이다.

내부 경고 코드는 다음 의미다.

| 코드 | 의미 | 공개 API 노출 |
|---|---|---|
| `EMPTY_RESULT` | 최종 태그가 0개다. 원문이 부족하거나 모두 필터링된 상태다. | 노출 안 함 |
| `LOW_CONFIDENCE` | 최종 태그가 3개 미만이다. 사용자에게 보여줄 수는 있지만 운영 관측 대상이다. | 노출 안 함 |
| `GENERIC_HEAVY` | 자동 제거된 일반어 후보가 5개 이상이다. 원문이 일반 안내문 위주일 수 있다. | 노출 안 함 |
| `GENERIC_TAGS_REMOVED` | 일반어/일반 phrase가 자동 제거됐다. 정상적인 품질 필터 동작이다. | 노출 안 함 |
| `OVERRIDE_HIDE_APPLIED` | 예외 안전장치로 숨김 override가 적용됐다. | 노출 안 함 |

## 렌더링 규칙

- 최대 7개다.
- 빈 배열이면 태그 영역을 숨긴다.
- 값 앞에 `#`을 저장하거나 API 요청에 다시 보내지 않는다.
- 같은 화면에서 기존 한옥 목록 카드의 `tags`와 같이 보일 수 있다. `tags`는 카드용 수동/기존 태그이고, `contentTags`는 상세 내용 기반 자체 추출 태그다.

예시:

```json
{
  "contentTags": ["한옥 골목", "공예 체험", "야간 산책"]
}
```

화면 표시 예시:

```text
#한옥 골목  #공예 체험  #야간 산책
```

## FE 활용 가이드

상세 화면에서는 제목·카테고리 아래 또는 설명문 위에 작은 chip row로 보여준다. 사용자가 설명문을 읽기 전에 핵심 경험을 먼저 훑는 용도다.

Odii 목록에서는 각 오디오 카드에 2~3개만 우선 노출하고, 상세 화면에서는 전체 `contentTags`를 노출하는 방식이 좋다. 목록 카드가 좁으면 첫 3개만 보여주고 나머지는 생략한다.

태그 클릭 동작은 이번 BE 계약에 포함되지 않는다. 초기 FE에서는 클릭 불가 chip으로 처리하고, 나중에 태그 기반 필터/검색이 필요해지면 별도 API 계약으로 확장한다.

권장 UI 처리:

- 1개 이상이면 chip row 표시
- 0개이면 영역 자체 숨김
- 긴 태그는 한 줄 chip 내부에서 말줄임 처리
- 상세 화면에서는 최대 7개 전부 표시 가능
- 목록 카드에서는 공간에 맞춰 2~3개만 표시
- `#`은 화면 렌더링에서만 붙임

예시 TypeScript 형태:

```ts
type ContentTagged = {
  contentTags: string[];
};

function displayTag(label: string) {
  return `#${label}`;
}
```

## 주의할 점

`contentTags`는 사용자 입력 태그나 제공기관 원본 태그가 아니다. 원문 내용에서 자동 추출한 보조 정보이므로 사용자가 수정하거나 서버에 다시 저장하는 값으로 다루지 않는다.

태그 순서는 BE가 계산한 중요도 순서다. FE에서 가나다순으로 재정렬하지 않는다.

기존 한옥 목록의 `tags`와 `contentTags`는 의미가 다르다. `tags`는 기존 카드 표시용 태그이고, `contentTags`는 상세 내용 기반 자동 추출 태그다.

## 생성 기준

BE는 TextRank 계열의 co-occurrence 점수와 관광 도메인 사전/불용어 보정을 함께 사용한다. 태그 pipeline은 내부적으로 source hash와 algorithm version을 계산하므로, 같은 source revision과 같은 원문에서는 같은 태그가 나온다.
