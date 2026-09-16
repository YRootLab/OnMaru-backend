# Content Tags 운영 가이드

## 목적

`contentTags`는 한국관광공사나 Odii가 제공하는 원본 태그가 아니라 OnMaru가 원문에서 자동 추출하는 보조 태그다. 운영의 기본은 자동 추출과 자동 품질 필터링이며, 사람이 매 콘텐츠를 직접 관리하는 구조가 아니다.

이 문서는 lexicon 조정, 품질 경고 해석, 변경 검증 기준을 정리한다.

## Lexicon 파일

태그 품질 튜닝 데이터는 다음 resource 파일에서 관리한다.

```text
modules/catalog/src/main/resources/content-tags/stopwords.txt
modules/catalog/src/main/resources/content-tags/generic-phrases.txt
modules/catalog/src/main/resources/content-tags/domain-phrases.txt
modules/catalog/src/main/resources/content-tags/generic-labels.txt
```

각 파일은 UTF-8 텍스트이며 한 줄에 하나의 값을 둔다. 빈 줄과 `#` 뒤 주석은 무시된다.

## 파일별 기준

`stopwords.txt`는 token 후보 단계에서 제거할 너무 일반적인 단어를 둔다. 조사나 문장 기능어, `관광`, `정보`, `안내`처럼 단독 태그 가치가 낮은 단어가 여기에 해당한다.

`generic-phrases.txt`는 2-gram phrase 단계에서 제거할 일반 표현을 둔다. 예를 들어 `추천 안내`, `관광지 정보`처럼 사용자에게 chip으로 보여줘도 의미가 약한 표현이다.

`domain-phrases.txt`는 관광, 한옥, 오디오 해설 맥락에서 의미 있는 phrase를 둔다. 여기에 들어간 phrase는 점수 boost를 받으므로, 실제 사용자 탐색 단서로 좋은 표현만 넣는다.

`generic-labels.txt`는 최종 label 단계에서 제거할 일반어다. 단독 label뿐 아니라 일반어끼리 결합된 phrase도 제거된다.

## 변경 기준

lexicon 변경은 작은 단위로 한다. 한 PR에서 너무 많은 단어를 한꺼번에 추가하면 어떤 단어가 품질을 바꿨는지 추적하기 어렵다.

`domain-phrases.txt`에는 브랜드성 문구나 캠페인성 노출 문구를 넣지 않는다. 그런 값은 자동 boost가 아니라 후속 override 안전장치로 다룬다.

`generic-labels.txt`나 `stopwords.txt`에 구체적인 장소명, 체험명, 지역명을 넣지 않는다. 과도하게 넣으면 유효한 태그가 사라질 수 있다.

## 품질 경고

내부 품질 경고는 FE 공개 API에 노출하지 않는다.

| 코드 | 의미 | 운영 해석 |
|---|---|---|
| `EMPTY_RESULT` | 최종 태그가 0개 | 원문 부족, 대본 누락, 과도한 필터링 가능성 |
| `LOW_CONFIDENCE` | 최종 태그가 3개 미만 | 노출은 가능하지만 운영 관측 대상 |
| `GENERIC_HEAVY` | 자동 제거 일반어가 5개 이상 | 원문이 일반 안내문 위주이거나 lexicon 조정 필요 |
| `GENERIC_TAGS_REMOVED` | 일반어/일반 phrase 제거 발생 | 정상적인 품질 필터 동작 |
| `OVERRIDE_HIDE_APPLIED` | 숨김 override 적용 | 예외 안전장치가 동작한 상태 |

## 검증 명령

lexicon이나 품질 기준을 바꾼 뒤에는 최소 다음을 실행한다.

```bash
./gradlew :modules:catalog:test --tests '*ContentTagExtractorTests' --tests '*ContentTagPipelineTests' --no-daemon
./gradlew :modules:catalog:test :modules:audio:test --no-daemon
git diff --check
```

OpenAPI나 fixture까지 바뀌는 경우에는 다음도 실행한다.

```bash
bash scripts/verify-contracts
```

## 후속 확장

DB에서 `content_tag_overrides`를 읽어 pipeline에 주입하는 adapter, 관리자 override API, 검색 ranking boost, 태그 클릭/필터 API, 품질 dashboard는 별도 Issue로 분리한다.
