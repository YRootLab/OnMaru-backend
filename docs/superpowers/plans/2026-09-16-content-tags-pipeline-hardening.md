# Content Tags Pipeline Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `contentTags` 생성 flow를 자동 품질 개선 중심의 표준 pipeline으로 정리한다.

**Architecture:** `modules:catalog`의 tag package에 pipeline, source hasher, automatic filter, override policy, quality report/result model을 추가한다. Odii sync mapper와 장소/한옥 query service는 extractor 직접 호출 대신 pipeline을 사용한다. 공개 API는 `contentTags: string[]` 계약을 유지한다.

**Tech Stack:** Java 21, JUnit 5, AssertJ, Spring Boot module tests, existing OpenAPI/fixture validation scripts.

## Global Constraints

- LLM을 사용하지 않는다.
- FE 공개 API는 `contentTags: string[]`만 노출한다.
- `PIN`/`HIDE` override는 예외 안전장치이며 기본 운영 방식이 아니다.
- 같은 source text와 같은 algorithm version에서는 deterministic 결과를 반환한다.
- 실제 DB repository adapter, 관리자 API, 검색 색인 반영은 이번 구현 범위에서 제외한다.

---

### Task 1: Pipeline Core

**Files:**
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/application/tags/ContentTagPipeline.java`
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/application/tags/ContentTagPipelineResult.java`
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/application/tags/ContentTagQualityReport.java`
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/application/tags/ContentTagOverride.java`
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/application/tags/ContentTagOverrideAction.java`
- Test: `modules/catalog/src/test/java/com/yrootlab/onmaru/catalog/application/tags/ContentTagPipelineTests.java`

**Interfaces:**
- Consumes: `ContentTagExtractor.extractRanked(ContentTagSource source, int maxTags)`
- Produces: `ContentTagPipeline.defaultPipeline()`
- Produces: `ContentTagPipeline.generate(ContentTagSource source, int maxTags)`
- Produces: `ContentTagPipeline.generate(ContentTagSource source, int maxTags, List<ContentTagOverride> overrides)`
- Produces: `ContentTagPipelineResult.publicLabels()`
- Produces: `ContentTagPipelineResult.rankedTags()`
- Produces: `ContentTagPipelineResult.sourceHash()`
- Produces: `ContentTagPipelineResult.algorithmVersion()`
- Produces: `ContentTagPipelineResult.qualityReport()`

- [x] **Step 1: Write failing pipeline tests**

```java
@Test
void filtersGenericTagsAndReportsQualityWithoutManualOverride() {
    var result = pipeline.generate(ContentTagSource.of(
            "관광 정보 안내",
            "관광",
            "관광 정보 안내 소개 코스 여행 궁궐 정원 산책 사진 명소",
            List.of()), 7);

    assertThat(result.publicLabels()).doesNotContain("관광", "정보", "안내", "소개", "코스", "여행");
    assertThat(result.publicLabels()).contains("궁궐", "정원 산책");
    assertThat(result.qualityReport().removedGenericCount()).isGreaterThan(0);
}

@Test
void appliesHideThenPinAsExceptionSafetyValve() {
    var result = pipeline.generate(ContentTagSource.of(
            "전주 한옥마을",
            "한옥",
            "한옥 골목과 공예 체험을 소개합니다.",
            List.of("한옥 골목")),
            3,
            List.of(
                    ContentTagOverride.hide("한옥 골목"),
                    ContentTagOverride.pin("전주 한옥마을")));

    assertThat(result.publicLabels()).startsWith("전주 한옥마을");
    assertThat(result.publicLabels()).doesNotContain("한옥 골목");
    assertThat(result.publicLabels()).hasSizeLessThanOrEqualTo(3);
}

@Test
void sourceHashIsStableForEquivalentWhitespace() {
    var left = pipeline.generate(ContentTagSource.of(" 전주  한옥마을 ", "한옥", "골목 산책", List.of(" 공예  체험 ")), 7);
    var right = pipeline.generate(ContentTagSource.of("전주 한옥마을", "한옥", "골목   산책", List.of("공예 체험")), 7);

    assertThat(left.sourceHash()).isEqualTo(right.sourceHash());
    assertThat(left.algorithmVersion()).isEqualTo("content-tags-v2");
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:catalog:test --tests '*ContentTagPipelineTests' --no-daemon`

Expected: FAIL because pipeline classes do not exist.

- [x] **Step 3: Implement minimal pipeline**

Add immutable records/classes with deterministic SHA-256 hashing, automatic generic filtering, HIDE removal, PIN prefixing, and max tag limiting.

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :modules:catalog:test --tests '*ContentTagPipelineTests' --no-daemon`

Expected: PASS.

### Task 2: Wire Pipeline Into Existing Flows

**Files:**
- Modify: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/application/query/detail/PlaceDetailQueryService.java`
- Modify: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiSourceMapper.java`
- Modify: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/query/OdiiStoryQueryService.java`
- Test: `modules/catalog/src/test/java/com/yrootlab/onmaru/catalog/application/query/detail/PlaceDetailQueryServiceTests.java`
- Test: `modules/audio/src/test/java/com/yrootlab/onmaru/audio/sync/OdiiSourceMapperTests.java`
- Test: `modules/audio/src/test/java/com/yrootlab/onmaru/audio/query/OdiiStoryQueryServiceTests.java`

**Interfaces:**
- Consumes: `ContentTagPipeline.generate(...).publicLabels()`
- Produces: existing public DTO `contentTags()` fields without API shape changes.

- [x] **Step 1: Write focused behavior assertions**

Assert Odii mapper and place detail fallback do not expose generic provider words and still preserve saved projection tags when present.

- [x] **Step 2: Run tests to verify failure where direct extractor still leaks generic tags**

Run: `./gradlew :modules:catalog:test :modules:audio:test --tests '*PlaceDetailQueryServiceTests' --tests '*OdiiSourceMapperTests' --tests '*OdiiStoryQueryServiceTests' --no-daemon`

- [x] **Step 3: Replace direct extractor dependencies with pipeline dependencies**

Construct `ContentTagPipeline.defaultPipeline()` in default constructors. Use `pipeline.generate(...).publicLabels()` in fallback paths.

- [x] **Step 4: Run module tests**

Run: `./gradlew :modules:catalog:test :modules:audio:test --no-daemon`

Expected: PASS.

### Task 3: Documentation And Verification

**Files:**
- Modify: `docs/toFE/content-tags.md`
- Modify: `troubleshooting-worklog/26.09.16 content-tags-hardening.md`

**Interfaces:**
- Consumes: implemented pipeline behavior.
- Produces: FE/operations documentation matching the final implementation.

- [x] **Step 1: Update docs**

Document that automatic quality filtering is the default and override is an exception safety valve.

- [x] **Step 2: Run final verification**

Run:

```bash
./gradlew :modules:catalog:test :modules:audio:test --no-daemon
./gradlew :apps:spring-api:test --tests '*PlaceDetailWebBoundaryTests' --tests '*OdiiStoryWebBoundaryTests' --tests '*SavedOdiiResourceWebBoundaryTests' --no-daemon
node --test scripts/test/migration-policy.test.mjs
bash scripts/verify-contracts
git diff --check
```

Expected: all commands exit 0.

### Task 4: Resource Lexicon And Quality Thresholds

**Files:**
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/application/tags/ContentTagLexicon.java`
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/application/tags/ContentTagQualityPolicy.java`
- Create: `modules/catalog/src/main/resources/content-tags/stopwords.txt`
- Create: `modules/catalog/src/main/resources/content-tags/generic-phrases.txt`
- Create: `modules/catalog/src/main/resources/content-tags/domain-phrases.txt`
- Create: `modules/catalog/src/main/resources/content-tags/generic-labels.txt`
- Modify: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/application/tags/ContentTagExtractor.java`
- Modify: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/application/tags/ContentTagPipeline.java`
- Test: `modules/catalog/src/test/java/com/yrootlab/onmaru/catalog/application/tags/ContentTagExtractorTests.java`
- Test: `modules/catalog/src/test/java/com/yrootlab/onmaru/catalog/application/tags/ContentTagPipelineTests.java`

**Interfaces:**
- Produces: `ContentTagLexicon.defaultLexicon()`
- Produces: `ContentTagExtractor.using(ContentTagLexicon lexicon)`
- Produces: `ContentTagQualityPolicy.defaultPolicy()`

- [x] **Step 1: Write failing resource lexicon tests**

Assert default extractor loads `전통 정원` from domain phrase resource and default pipeline removes `추천` from generic label resource.

- [x] **Step 2: Run tests to verify failure**

Run: `./gradlew :modules:catalog:test --tests '*ContentTagExtractorTests.loadsDomainPhrasesFromResourceLexicon' --tests '*ContentTagPipelineTests.loadsGenericLabelsFromResourceLexicon' --no-daemon`

Expected: initial failure before lexicon implementation.

- [x] **Step 3: Implement resource lexicon and quality policy**

Move stopwords, generic phrases, domain phrases, and generic labels into UTF-8 resource files. Add explicit `GENERIC_HEAVY` threshold through `ContentTagQualityPolicy`.

- [x] **Step 4: Run focused and module tests**

Run:

```bash
./gradlew :modules:catalog:test --tests '*ContentTagPipelineTests.reportsGenericHeavyWhenAutomaticFilterRemovesManyGenericCandidates' --no-daemon
./gradlew :modules:catalog:test :modules:audio:test --no-daemon
```

Expected: PASS.
