# Content Tags Pipeline Hardening Design

## 목표

Issue #208의 `contentTags`를 단순 응답 보강 필드에서 운영 가능한 자동 태그 파이프라인으로 확장한다.

이번 고도화의 중심은 운영자가 태그를 계속 수동 관리하지 않아도 되는 자동 품질 개선이다. `PIN`/`HIDE` override는 기본 운영 방식이 아니라 예외 상황에서만 쓰는 안전장치로 둔다.

## 문제 정의

현재 구현은 FE 공개 계약과 기본 추출, 일부 저장 슬롯을 제공한다. 하지만 다음 한계가 남아 있다.

- `ContentTagExtractor` 호출이 query service와 sync mapper에 흩어져 있다.
- 태그 생성의 표준 흐름이 없어 source hash, 품질 판단, override 적용 순서가 고정되어 있지 않다.
- 장소/한옥은 저장된 태그가 없으면 응답 시점 fallback 계산에 의존한다.
- Odii는 sync 단계에서 태그를 만들지만, 원문 hash와 알고리즘 버전을 기준으로 재계산 여부를 설명하는 결과 모델이 없다.
- DB에는 override 테이블이 있지만, override는 아직 “운영자가 항상 관리해야 하는 기능”처럼 오해될 수 있다.
- 태그가 0개이거나 너무 일반적인 태그만 남는 품질 문제를 pipeline 결과로 관측하기 어렵다.

## 설계 원칙

- FE 공개 API는 계속 `contentTags: string[]`만 노출한다.
- LLM은 사용하지 않는다.
- 기본 동작은 자동 추출과 자동 품질 필터링이다.
- 운영자 override는 자동 추출 실패나 법적/브랜드 리스크 같은 예외에만 적용한다.
- 태그 결과는 같은 source text와 같은 algorithm version에서 deterministic 해야 한다.
- public contract와 internal metadata를 분리한다.
- 실제 DB repository adapter는 이번 범위의 필수 구현으로 보지 않는다. 대신 후속 DB 저장 구현에서 그대로 연결할 수 있는 interface/result model을 만든다.

## 권장 구조

새 pipeline 계층을 `modules/catalog`의 tag package에 둔다. Odii도 catalog의 content tag 도메인 컴포넌트를 재사용한다.

```text
ContentTagSource
  -> ContentTagPipeline.generate(...)
      -> sourceHash 계산
      -> ContentTagExtractor.extractRanked(...)
      -> ContentTagAutomaticFilter 적용
      -> ContentTagOverridePolicy 적용
      -> ContentTagQualityReport 생성
  -> ContentTagPipelineResult
      -> publicLabels()
      -> rankedTags()
      -> sourceHash()
      -> algorithmVersion()
      -> qualityReport()
```

## 컴포넌트

### `ContentTagPipeline`

태그 생성의 단일 진입점이다.

입력:

- `ContentTagSource`
- 최대 태그 수
- optional override 목록

출력:

- 공개 응답에 바로 쓸 `publicLabels`
- 내부 저장에 쓸 ranked tags
- source hash
- algorithm version
- quality report

역할:

- extractor 직접 호출을 감싼다.
- 자동 필터와 override 적용 순서를 고정한다.
- query service와 sync mapper가 같은 규칙을 쓰게 한다.

### `ContentTagSourceHasher`

태그 입력 원문의 canonical hash를 만든다.

hash 입력에는 다음이 포함된다.

- title
- category
- body
- highlights

정규화 규칙:

- null은 빈 문자열로 본다.
- 앞뒤 공백을 제거한다.
- 연속 공백은 하나로 줄인다.
- 필드 구분자를 명확히 넣어 `"ab" + "c"`와 `"a" + "bc"`가 같은 hash가 되지 않게 한다.

이 hash는 즉시 공개 API에 노출하지 않는다. DB 저장, 재생성 판단, 작업 로그, 품질 리포트에만 사용한다.

### `ContentTagAutomaticFilter`

운영자가 직접 숨기지 않아도 자동으로 품질이 낮은 태그를 제거한다.

필터링 대상:

- 너무 일반적인 태그: `관광`, `정보`, `소개`, `안내`, `코스`, `여행`
- 한 글자 태그
- 중복 태그
- 같은 label의 공백/대소문자 차이
- 숫자만 있는 태그
- source text에 비해 의미가 약한 filler phrase

이 단계가 중요한 이유는 override를 수동 운영 도구로 남용하지 않기 위해서다.

필터와 boost에 쓰는 단어 목록은 Java 코드 상수가 아니라 resource lexicon으로 관리한다.

```text
modules/catalog/src/main/resources/content-tags/stopwords.txt
modules/catalog/src/main/resources/content-tags/generic-phrases.txt
modules/catalog/src/main/resources/content-tags/domain-phrases.txt
modules/catalog/src/main/resources/content-tags/generic-labels.txt
```

이렇게 분리하면 `추천`, `관광`, `소개` 같은 일반어 제거와 `전통 정원`, `한옥 골목` 같은 도메인 phrase boost를 코드 수정 없이 데이터 파일 기준으로 검토할 수 있다.

### `ContentTagOverridePolicy`

예외 보정을 담당한다.

`HIDE`:

- 자동 추출 결과에 있더라도 최종 결과에서 제거한다.
- 금칙어, 오탐 태그, 법적/브랜드 리스크 태그를 막는 용도다.

`PIN`:

- 자동 추출 결과에 없더라도 최종 결과 앞쪽에 배치한다.
- 특정 캠페인, 검수 완료 대표 태그, 중요한 지역/경험 키워드를 보존하는 용도다.

운영 원칙:

- override는 전체 태그 운영의 중심이 아니다.
- 정상 콘텐츠의 95~99%는 자동 pipeline만으로 처리한다.
- override는 1~5%의 예외 처리로 제한하는 것을 목표로 한다.

적용 순서:

```text
자동 추출
  -> 자동 품질 필터
  -> HIDE 제거
  -> PIN 앞쪽 배치
  -> 최대 7개 제한
```

### `ContentTagQualityReport`

태그 결과의 품질 상태를 표현한다.

필드 예시:

- `generatedCount`
- `finalCount`
- `removedGenericCount`
- `hiddenOverrideCount`
- `pinnedOverrideCount`
- `emptyResult`
- `lowConfidence`
- `warnings`

품질 리포트는 FE에 노출하지 않는다. 테스트, sync 결과, 운영 로그, 나중의 admin 검수 화면에서 사용할 내부 신호다.

기본 품질 threshold는 `ContentTagQualityPolicy`가 소유한다.

- `finalCount == 0`: `EMPTY_RESULT`
- `finalCount < 3`: `LOW_CONFIDENCE`
- `removedGenericCount >= 5`: `GENERIC_HEAVY`

threshold는 공개 API 계약이 아니라 운영 관측성 기준이다.

### `OdiiContentTagQualitySummary`

Odii sync는 story별 `ContentTagQualityReport`를 집계해 sync 결과에 싣는다.

집계 항목:

- `storyCount`
- `emptyStoryCount`
- `lowConfidenceStoryCount`
- `removedGenericCount`
- `hiddenOverrideCount`
- `pinnedOverrideCount`

이 값은 공개 FE API 응답이 아니라 sync/publish 관측성 신호다. 원문 대본이 부실하거나 자동 필터가 과도하게 작동한 revision을 운영자가 빠르게 발견하는 데 사용한다.

## 데이터 흐름

### Odii

Odii는 sync mapping 단계에서 pipeline을 호출한다.

```text
OdiiSourceStory
  -> OdiiSourceMapper
  -> ContentTagPipeline.generate(...)
  -> OdiiStoryVersion.contentTags
  -> OdiiStoryVersion.contentTagQualityReport
  -> OdiiSyncResult.contentTagQuality
  -> ActiveRevisionOdiiStoryQueryStore
  -> OdiiStoryProjection.contentTags
  -> OdiiStoryQueryService
  -> API contentTags
```

저장된 `OdiiStoryVersion.contentTags`가 있으면 API는 그 값을 우선 사용한다. 없는 fixture나 과거 projection에 대해서만 fallback을 허용한다.

### 장소/한옥

장소/한옥은 현재 DB-backed catalog publish adapter가 완성되어 있지 않다. 따라서 이번 단계에서는 query service fallback도 pipeline을 타게 만들어 규칙을 통일한다.

```text
PlaceProjection
  -> 저장 contentTags 있으면 우선 사용
  -> 없으면 ContentTagPipeline.generate(...)
  -> CanonicalPlaceDetail/HanokDetail.contentTags
```

후속 DB adapter가 붙으면 `catalog_place_content_tag_versions`에 pipeline result를 저장하고, fallback 비중을 줄인다.

## API 계약

공개 API는 바꾸지 않는다.

```json
{
  "contentTags": ["한옥 골목", "공예 체험", "야간 산책"]
}
```

다음 metadata는 공개 API에 노출하지 않는다.

- score
- source
- sourceHash
- algorithmVersion
- qualityReport
- override 적용 여부

## 업데이트 시점

Odii:

- source sync가 새 story version을 만들 때 pipeline을 실행한다.
- 제목, 오디오 제목, 대본이 바뀌면 source hash가 달라진다.
- algorithm version이 바뀌면 재생성 대상이 된다.

장소/한옥:

- 현재는 projection 저장 태그 우선, 없으면 query fallback이다.
- 후속 catalog publish adapter 구현 후에는 publish 시점 저장으로 전환한다.

## 테스트 전략

- pipeline 단위 테스트로 source hash 안정성, 자동 필터, override 적용 순서, 최대 7개 제한을 검증한다.
- Odii mapper 테스트로 sync mapping 단계에서 pipeline 결과가 story version에 저장되는지 검증한다.
- 장소/한옥 query service 테스트로 저장 태그 우선, fallback pipeline 사용을 검증한다.
- API boundary 테스트로 공개 응답이 여전히 `string[]`만 노출하는지 확인한다.
- contract 검증으로 OpenAPI와 fixture가 깨지지 않는지 확인한다.

## 이번 범위에서 하지 않는 것

- 관리자 override API
- 운영자 화면
- 실제 DB repository adapter 완성
- 검색 색인 boost 반영
- LLM 기반 태그 생성
- FE에 score나 품질 리포트 노출

## 후속 작업

- catalog publish adapter가 생기면 `catalog_place_content_tag_versions`에 pipeline result를 저장한다.
- audio persistence adapter가 생기면 `audio_story_content_tag_versions`에 source hash와 score를 저장한다.
- 품질 리포트를 sync run summary나 operations dashboard에 연결한다.
- 태그 기반 검색 boost와 추천 feature를 별도 Issue로 분리한다.
