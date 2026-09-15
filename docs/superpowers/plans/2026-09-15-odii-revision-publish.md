# Odii Revision Publish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 검증된 Odii 응답을 언어별 spot/story revision으로 수집하고, 삭제 tombstone과 이전 LKG를 보존하면서 spot/story를 원자 게시한다.

**Architecture:** `adapters:tourism-api`는 provider envelope parsing과 typed source record 생성만 담당한다. 신규 `modules:audio`는 provider-independent mapping, revision staging, tombstone 판정, catalog의 공통 `PublicationStore` 계약을 통한 원자 게시를 담당한다. 공개 조회 API와 place link는 후속 Issue #103/#104 범위로 남긴다.

**Tech Stack:** Java 21, Gradle Kotlin DSL, Jackson, JUnit 5, AssertJ

## Global Constraints

- GitHub Issue #96의 범위와 acceptance criteria를 source of truth로 사용한다.
- `provider + tid + tlid`, `provider + stid + stlid`를 언어별 stable identity로 사용한다.
- spot/story 전체 stage가 완료되기 전에는 active revision과 watermark를 변경하지 않는다.
- full sync에서 사라진 identity는 2회 연속 성공 관측 후 `DELETED` tombstone으로 게시한다.
- provider 공식 script가 비어 있으면 transcript provenance를 `MISSING`으로 기록하며 추정 대본을 만들지 않는다.
- 공개 API와 canonical place link는 구현하지 않는다.

---

### Task 1: Odii Provider Envelope Parser

**Files:**
- Create: `adapters/tourism-api/src/main/java/com/yrootlab/onmaru/tourism/audio/client/OdiiEnvelopeParser.java`
- Create: `adapters/tourism-api/src/main/java/com/yrootlab/onmaru/tourism/audio/client/OdiiPage.java`
- Create: `adapters/tourism-api/src/main/java/com/yrootlab/onmaru/tourism/audio/client/OdiiSourceItem.java`
- Create: `adapters/tourism-api/src/main/java/com/yrootlab/onmaru/tourism/audio/client/OdiiParseException.java`
- Create: `adapters/tourism-api/src/main/java/com/yrootlab/onmaru/tourism/audio/client/OdiiHttpClient.java`
- Create: `adapters/tourism-api/src/main/java/com/yrootlab/onmaru/tourism/audio/client/OdiiClientProperties.java`
- Create: `adapters/tourism-api/src/main/java/com/yrootlab/onmaru/tourism/audio/client/OdiiUriBuilder.java`
- Create: `adapters/tourism-api/src/test/java/com/yrootlab/onmaru/tourism/audio/client/OdiiEnvelopeParserTests.java`
- Create: `adapters/tourism-api/src/test/java/com/yrootlab/onmaru/tourism/audio/client/OdiiHttpClientTests.java`

**Interfaces:**
- Consumes: qualification fixtures under `testing/fixtures/provider/odii`.
- Produces: `OdiiEnvelopeParser.parse(String operation, byte[] body): OdiiPage`; `OdiiPage(items, page, pageSize, totalCount, lastPage)`.

- [x] **Step 1: Write failing fixture parser tests**

```java
@Test
void parsesSuccessArrayAndPagination() throws Exception {
    OdiiPage page = parser.parse("storyBasedList", fixture("story_based_first_page.json"));
    assertThat(page.items()).hasSize(3);
    assertThat(page.items().getFirst().stid()).isEqualTo("45");
    assertThat(page.lastPage()).isFalse();
}

@Test
void acceptsEmptyStringItemsAsEmptyPage() throws Exception {
    assertThat(parser.parse("storySearchList", fixture("empty_search.json")).items()).isEmpty();
}

@Test
void rejectsProviderErrorEnvelope() throws Exception {
    assertThatThrownBy(() -> parser.parse("storySearchList", fixture("provider_error_missing_key.json")))
            .isInstanceOf(OdiiParseException.class);
}
```

- [x] **Step 2: Run RED**

Run: `./gradlew :adapters:tourism-api:test --tests '*OdiiEnvelopeParserTests' --no-daemon`

Expected: FAIL because the Odii parser types do not exist.

- [x] **Step 3: Implement strict success/error envelope parsing**

```java
public OdiiPage parse(String operation, byte[] body) {
    JsonNode root = objectMapper.readTree(body);
    JsonNode response = root.path("response");
    if (!"0000".equals(response.path("header").path("resultCode").asText())) {
        throw new OdiiParseException("ODII_PROVIDER_ERROR");
    }
    JsonNode bodyNode = response.path("body");
    List<OdiiSourceItem> items = readItems(operation, bodyNode.path("items"));
    int page = positiveInt(bodyNode, "pageNo");
    int pageSize = positiveInt(bodyNode, "numOfRows");
    int totalCount = nonNegativeInt(bodyNode, "totalCount");
    return new OdiiPage(items, page, pageSize, totalCount, (long) page * pageSize >= totalCount);
}
```

- [x] **Step 4: Run GREEN**

Run: `./gradlew :adapters:tourism-api:test --tests '*OdiiEnvelopeParserTests' --no-daemon`

Expected: PASS for array, empty, pagination, provider error, and malformed item cases.

- [x] **Step 5: Add failing HTTP resilience and URI tests**

The client tests require one retry for HTTP 503, no retry for provider auth envelopes, attempt/page timeout budgets, encoded language and pagination query parameters, and redacted service-key diagnostics.

- [x] **Step 6: Implement and verify the bounded HTTP client**

Run: `./gradlew :adapters:tourism-api:test --tests '*Odii*Tests' --no-daemon`

Expected: PASS without logging or returning the service key in errors.

### Task 2: Language Identity And Transcript Mapping

**Files:**
- Create: `modules/audio/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Create: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiSourceStory.java`
- Create: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiSpotIdentity.java`
- Create: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiStoryIdentity.java`
- Create: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiSpotVersion.java`
- Create: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiStoryVersion.java`
- Create: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/TranscriptProvenance.java`
- Create: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiSourceMapper.java`
- Create: `modules/audio/src/test/java/com/yrootlab/onmaru/audio/sync/OdiiSourceMapperTests.java`
- Create: `adapters/tourism-api/src/main/java/com/yrootlab/onmaru/tourism/audio/mapping/OdiiSourceItemMapper.java`
- Modify: `adapters/tourism-api/build.gradle.kts`

**Interfaces:**
- Consumes: `OdiiSourceItem` from Task 1.
- Produces: `OdiiSourceStory`; `OdiiSourceMapper.map(OdiiSourceStory): OdiiMappedStory` containing one spot version and one story version.

- [x] **Step 1: Write failing language/provenance tests**

```java
@Test
void preservesProviderLanguageIdsInsteadOfCollapsingByTidAndStid() {
    var korean = mapper.map(source("89", "300", "562", "1204", "ko", "대본"));
    var english = mapper.map(source("89", "301", "562", "2187", "en", "Script"));
    assertThat(korean.spot().identity()).isNotEqualTo(english.spot().identity());
    assertThat(korean.story().identity()).isNotEqualTo(english.story().identity());
}

@Test
void emptyOfficialScriptIsMissingAndNeverEstimated() {
    var mapped = mapper.map(source("146", "412", "732", "1520", "ko", ""));
    assertThat(mapped.story().transcriptProvenance()).isEqualTo(TranscriptProvenance.MISSING);
    assertThat(mapped.story().script()).isNull();
}
```

- [x] **Step 2: Run RED**

Run: `./gradlew :modules:audio:test --tests '*OdiiSourceMapperTests' --no-daemon`

Expected: FAIL because `modules:audio` and mapping types do not exist.

- [x] **Step 3: Implement validated mapping**

```java
public OdiiMappedStory map(OdiiSourceStory source) {
    var spotIdentity = new OdiiSpotIdentity(PROVIDER, required(source.tid()), required(source.tlid()), language(source.langCode()));
    var storyIdentity = new OdiiStoryIdentity(PROVIDER, required(source.stid()), required(source.stlid()), language(source.langCode()));
    String script = blankToNull(source.script());
    return new OdiiMappedStory(
            new OdiiSpotVersion(spotIdentity, required(source.title()), source.longitude(), source.latitude(), source.modifiedAt()),
            new OdiiStoryVersion(storyIdentity, spotIdentity, title(source), script,
                    script == null ? TranscriptProvenance.MISSING : TranscriptProvenance.OFFICIAL,
                    blankToNull(source.audioUrl()), blankToNull(source.imageUrl()), source.durationSeconds(), source.modifiedAt())
    );
}
```

- [x] **Step 4: Run GREEN**

Run: `./gradlew :modules:audio:test --tests '*OdiiSourceMapperTests' --no-daemon`

Expected: PASS for ko/en identity separation, blank script/audio, duration, coordinates, timestamp, and required ID validation.

### Task 3: Atomic Audio Revision Publication

**Files:**
- Create: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/AudioRevisionStore.java`
- Create: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/AudioRevisionStage.java`
- Create: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiPageSource.java`
- Create: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiSyncCommand.java`
- Create: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiSyncResult.java`
- Create: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/sync/OdiiRevisionSyncService.java`
- Create: `modules/audio/src/test/java/com/yrootlab/onmaru/audio/sync/OdiiRevisionSyncServiceTests.java`

**Interfaces:**
- Consumes: `OdiiPageSource.fetch(language, page): OdiiSourcePage`, `AudioRevisionStore extends PublicationStore`, catalog `DatasetPublicationService`.
- Produces: a complete staged audio revision and `OdiiSyncResult(status, stagedRevisionId, itemCount, tombstoneCount)`.

- [x] **Step 1: Write failing atomic publication tests**

```java
@Test
void lastPageFailureLeavesPreviousLkgAndWatermarkUntouched() {
    source.failOn("en", 2);
    OdiiSyncResult result = service.sync(command(List.of("ko", "en")));
    assertThat(result.status()).isEqualTo(OdiiSyncStatus.SOURCE_FAILED);
    assertThat(store.activeRevision()).isEqualTo(baseRevision);
    assertThat(store.watermark()).isEqualTo(previousWatermark);
}

@Test
void publishesSpotAndStoriesTogetherAfterEveryLanguageCompletes() {
    OdiiSyncResult result = service.sync(command(List.of("ko", "en")));
    assertThat(result.status()).isEqualTo(OdiiSyncStatus.PUBLISHED);
    assertThat(store.activeSnapshot().spots()).hasSize(2);
    assertThat(store.activeSnapshot().stories()).hasSize(2);
}

@Test
void missingIdentityBecomesTombstoneOnlyAfterTwoSuccessfulFullRuns() {
    service.sync(command(List.of("ko")));
    source.removeStory("1204");
    service.sync(nextCommand());
    assertThat(store.activeStory("1204").status()).isEqualTo(AudioStatus.ACTIVE);
    service.sync(nextCommand());
    assertThat(store.activeStory("1204").status()).isEqualTo(AudioStatus.DELETED);
}
```

- [x] **Step 2: Run RED**

Run: `./gradlew :modules:audio:test --tests '*OdiiRevisionSyncServiceTests' --no-daemon`

Expected: FAIL because revision orchestration types do not exist.

- [x] **Step 3: Implement stage, missing-observation, and publish orchestration**

```java
public OdiiSyncResult sync(OdiiSyncCommand command) {
    AudioRevisionStage stage = store.openStage(command.dataset(), command.expectedActiveRevisionId(), clock.instant());
    try {
        for (String language : command.languages()) {
            int page = 1;
            do {
                OdiiSourcePage sourcePage = source.fetch(language, page);
                store.stage(stage.revisionId(), sourcePage.items().stream().map(mapper::map).toList());
                page++;
                if (sourcePage.lastPage()) break;
            } while (true);
        }
    } catch (OdiiSourceException exception) {
        store.failStage(stage.revisionId(), "SOURCE_FAILED");
        return OdiiSyncResult.sourceFailed(stage.revisionId());
    }
    long tombstones = store.markMissingAfterConsecutiveSuccesses(stage.revisionId(), 2);
    store.completeStage(stage.revisionId());
    PublicationResult publication = publisher.publish(command.publication(stage.revisionId(), tombstones));
    return OdiiSyncResult.from(stage.revisionId(), publication, store.stagedItemCount(stage.revisionId()), tombstones);
}
```

- [x] **Step 4: Run GREEN and module suite**

Run: `./gradlew :modules:audio:test --no-daemon`

Expected: PASS for multi-page/language completion, partial failure LKG, stale lease/base revision, empty full sync review, and two-success tombstones.

### Task 4: Repository Integration And Evidence

**Files:**
- Modify: `apps/spring-api/build.gradle.kts`
- Modify: `.github/workflows/ci.yml`
- Create: `troubleshooting-worklog/26.09.15 odii-revision-publish.md`
- Modify: `handoff.md`

**Interfaces:**
- Consumes: completed parser and audio module suites.
- Produces: CI-visible audio test task and Korean worklog tied to Issue #96.

- [x] **Step 1: Add audio module to application and CI dependency graph**

```kotlin
implementation(project(":modules:audio"))
```

```yaml
- name: Audio module tests
  run: ./gradlew :modules:audio:test --no-daemon
```

- [x] **Step 2: Record RED/GREEN evidence and handoff**

The worklog records Issue/dependencies, parser and mapping contracts, partial publish/LKG behavior, tombstone rule, exact RED failures, and final verification commands. The top handoff section records branch, Issue #96, touched modules, verification, and PR next step.

- [x] **Step 3: Run focused verification**

Run: `./gradlew :adapters:tourism-api:test :modules:audio:test :apps:spring-api:test --no-daemon`

Expected: BUILD SUCCESSFUL.

- [x] **Step 4: Run repository verification**

Run: `./gradlew test --no-daemon`

Run: `cd ai && uv run ruff check . && uv run mypy && uv run pytest`

Run: `node --test scripts/test/*.test.mjs && bash scripts/verify-contracts && git diff --check`

Expected: every command exits 0. The local Python 3.9 LibreSSL warning from contract validation is allowed when validation still exits 0.

- [ ] **Step 5: Reconcile and prepare PR**

Run: `node scripts/print-branch-issue.mjs`

Expected: `96`.

Review `git status --short`, `git diff --stat`, Issue #96 acceptance criteria, and open PR list. Commit with `feat(audio): Odii revision 게시 구현`, push the branch, and create a Korean PR into `develop` containing `Closes #96` and all verification evidence.
