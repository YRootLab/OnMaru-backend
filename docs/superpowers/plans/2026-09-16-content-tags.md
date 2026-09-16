# Content Tags Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관광지/한옥/Odii 응답에 LLM 없이 자체 추출한 `contentTags`를 제공한다.

**Architecture:** `modules:catalog`에 순수 Java `ContentTagExtractor`를 두고 장소와 Odii 쿼리 서비스가 응답 생성 시 호출한다. 현재 인메모리 projection에서는 결정적으로 계산하고, 향후 publish projection 저장 단계로 이동할 수 있도록 extractor를 독립 컴포넌트로 유지한다.

**Tech Stack:** Java 21, JUnit 5, AssertJ, OpenAPI YAML, JSON contract fixtures.

## Global Constraints

- LLM을 사용하지 않는다.
- `contentTags`는 `#` 없는 문자열 배열이며 최대 7개다.
- 입력이 부족하거나 후보가 없으면 빈 배열을 반환한다.
- 기존 `schemaVersion`은 `1.2`를 유지한다.
- 기존 한옥 목록 `tags` 필드는 이름을 바꾸지 않는다.

---

### Task 1: Extractor

**Files:**
- Create: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/application/tags/ContentTagExtractor.java`
- Test: `modules/catalog/src/test/java/com/yrootlab/onmaru/catalog/application/tags/ContentTagExtractorTests.java`

**Interfaces:**
- Produces: `ContentTagExtractor.defaultExtractor()`
- Produces: `List<String> extract(ContentTagSource source, int maxTags)`
- Produces: `ContentTagSource.of(String title, String category, String body, List<String> highlights)`

- [x] **Step 1: Write failing extractor tests**

```java
assertThat(extractor.extract(source, 7))
        .containsSubsequence("한옥 골목", "공예 체험", "야간 산책");
```

- [x] **Step 2: Run test and verify RED**

Run: `./gradlew :modules:catalog:test --tests '*ContentTagExtractorTests'`

- [x] **Step 3: Implement extractor**

Implement normalization, candidate generation, co-occurrence/frequency scoring, domain phrase boost, stopword filtering, max 7 stable ordering.

- [x] **Step 4: Run extractor tests and verify GREEN**

Run: `./gradlew :modules:catalog:test --tests '*ContentTagExtractorTests'`

### Task 2: Query DTOs

**Files:**
- Modify: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/application/query/detail/CanonicalPlaceDetail.java`
- Modify: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/application/query/detail/HanokDetail.java`
- Modify: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/query/OdiiStorySummary.java`
- Modify: `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/application/query/detail/PlaceDetailQueryService.java`
- Modify: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/query/OdiiStoryQueryService.java`
- Test: existing query service tests.

**Interfaces:**
- Consumes: `ContentTagExtractor.defaultExtractor()`
- Produces: `contentTags()` accessors on `CanonicalPlaceDetail`, `HanokDetail`, `OdiiStorySummary`

- [x] **Step 1: Write failing query tests**

Assert canonical detail, hanok detail, Odii list/detail summaries expose deterministic `contentTags`.

- [x] **Step 2: Run focused tests and verify RED**

Run: `./gradlew :modules:catalog:test --tests '*PlaceDetailQueryServiceTests'`
Run: `./gradlew :modules:audio:test --tests '*OdiiStoryQueryServiceTests'`

- [x] **Step 3: Wire extractor into services and records**

Add `contentTags` constructor fields and call extractor from response mapping.

- [x] **Step 4: Run focused tests and verify GREEN**

Run focused catalog/audio tests again.

### Task 3: Contracts And FE Handoff

**Files:**
- Modify: `docs/contracts/openapi/r1.openapi.yaml`
- Modify: `docs/contracts/openapi/r2-map-audio-insights.openapi.yaml`
- Modify: `docs/contracts/fixtures/r1/place-detail-normal.json`
- Modify: `docs/contracts/fixtures/r1/hanok-detail-normal.json`
- Modify: `docs/contracts/fixtures/r2/odii-stories-normal.json`
- Modify: `docs/contracts/fixtures/r2/odii-story-detail-normal.json`
- Modify: `docs/contracts/fixtures/r2/odii-story-detail-missing-transcript.json`
- Create: `docs/toFE/content-tags.md`

**Interfaces:**
- Consumes: `contentTags` response field.
- Produces: FE-readable contract note.

- [x] **Step 1: Update OpenAPI schemas**

Add required `contentTags` arrays to canonical place detail, hanok detail, and Odii story summary.

- [x] **Step 2: Update fixtures**

Add representative content tags to normal and missing-transcript fixtures.

- [x] **Step 3: Write FE handoff**

Document source, shape, rendering, empty-state behavior, and non-LLM guarantee.

- [x] **Step 4: Run repository verification**

Run focused Gradle tests and available contract/document hygiene checks.
