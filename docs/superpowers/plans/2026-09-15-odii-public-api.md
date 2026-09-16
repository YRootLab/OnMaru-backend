# Odii Public API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 활성 Odii revision의 공개 story 목록과 상세를 언어 fallback, 자막 provenance, 안전한 audio URL 정책과 함께 제공한다.

**Architecture:** `modules/audio/query`가 active snapshot read model과 조회 정책을 소유한다. Spring `web/audio`는 로그인 세션을 선택적으로 해석하고 query service 결과를 직렬화하며, 도메인 예외를 공통 HTTP error envelope로 변환한다. production persistence가 아직 없으므로 기본 runtime store는 fail-closed로 구성하고, HTTP 통합 테스트는 test-owned query port를 주입한다. #104의 승인 장소 연결은 nullable resolver port 뒤에 둔다.

**Tech Stack:** Java 21, Spring Boot 3, JUnit 5, AssertJ, MockMvc, OpenAPI 3.1, JSON Schema fixture validator

## Global Constraints

- `schemaVersion`은 `1.2`를 유지한다.
- provider 전용 `tid`, `tlid`, `stid`, `stlid`, `serviceKey`와 secret query는 공개 응답에 포함하지 않는다.
- `ACTIVE` story와 spot만 공개하며 `HIDDEN` 또는 `DELETED` story는 상세에서도 404로 처리한다.
- runtime 성공 응답을 위한 seed를 두지 않으며 production store가 연결되기 전 기본 구성은 503으로 fail-closed한다.
- requested language가 없으면 `ko-KR`을 우선 fallback하고 실제 반환 언어와 `languageStatus`를 함께 제공한다.
- transcript provenance는 `OFFICIAL`, `ESTIMATED`, `MISSING`으로 구분하고 결측을 빈 transcript로 직렬화한다.

---

### Task 1: Active Revision Odii Query Domain

**Files:**
- Create: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/query/*.java`
- Create: `modules/audio/src/test/java/com/yrootlab/onmaru/audio/query/OdiiStoryQueryServiceTests.java`

**Interfaces:**
- Consumes: `OdiiStoryQuery(language, category, regionCode, limit, cursor, memberId)`와 `OdiiStoryQueryStore.activeSnapshot()`
- Produces: `OdiiStoryQueryService.list(OdiiStoryQuery)`, `OdiiStoryQueryService.detail(String, String, Optional<UUID>)`, `OdiiStoryPage`, `OdiiStoryDetail`

- [x] **Step 1: Write failing domain tests**

```java
@Test
void fallsBackToKoreanAndMarksMissingTranscriptWithoutPublishingHiddenStories() {
    var service = serviceWithExactFallbackMissingAndHiddenStories();

    var page = service.list(OdiiStoryQuery.firstPage("en-US", 20, Optional.empty()));
    var detail = service.detail("odii-story-jeonju-hanok-01", "en-US", Optional.empty());

    assertThat(page.languageStatus()).isEqualTo(OdiiLanguageStatus.FALLBACK);
    assertThat(detail.transcriptStatus()).isEqualTo(OdiiTranscriptStatus.MISSING);
    assertThat(detail.transcript()).isEmpty();
    assertThatThrownBy(() -> service.detail("odii-story-hidden-01", "ko-KR", Optional.empty()))
            .isInstanceOf(OdiiStoryNotFoundException.class);
}
```

- [x] **Step 2: Verify RED**

Run: `./gradlew :modules:audio:test --tests '*OdiiStoryQueryServiceTests' --no-daemon`

Expected: compilation failure because the `audio.query` API does not exist.

- [x] **Step 3: Implement the query read model and policies**

```java
public interface OdiiStoryQueryStore {
    OdiiActiveSnapshot activeSnapshot();
}

public final class OdiiStoryQueryService {
    public OdiiStoryPage list(OdiiStoryQuery query) {
        var snapshot = store.activeSnapshot();
        var selection = selectLanguage(snapshot, query.language());
        return paginate(selection, query);
    }

    public OdiiStoryDetail detail(String storyId, String language, Optional<UUID> memberId) {
        var snapshot = store.activeSnapshot();
        var selection = selectDetail(snapshot, storyId, language);
        requireSafeAudioUrl(selection.projection());
        return toDetail(selection, memberId);
    }
}
```

- [x] **Step 4: Verify GREEN and refactor**

Run: `./gradlew :modules:audio:test --tests '*OdiiStoryQueryServiceTests' --no-daemon`

Expected: all query service tests pass, including exact/fallback/missing, active revision, cursor, unsafe URL, and hidden/deleted cases.

### Task 2: Spring Odii HTTP Boundary

**Files:**
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/audio/OdiiStoryController.java`
- Create: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/audio/OdiiStoryConfiguration.java`
- Create: `apps/spring-api/src/test/java/com/yrootlab/onmaru/web/audio/OdiiStoryWebBoundaryTests.java`

**Interfaces:**
- Consumes: Task 1 `OdiiStoryQueryService`, optional `__Host-onmaru-session`
- Produces: `GET /api/v1/odii/stories`, `GET /api/v1/odii/stories/{storyId}`

- [x] **Step 1: Write failing MockMvc contract tests**

```java
mockMvc.perform(get("/api/v1/odii/stories/odii-story-jeonju-hanok-01")
                .param("language", "ko-KR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schemaVersion").value("1.2"))
        .andExpect(jsonPath("$.transcriptStatus").value("OFFICIAL"))
        .andExpect(jsonPath("$.story.storyId").value("odii-story-jeonju-hanok-01"));
```

- [x] **Step 2: Verify RED**

Run: `./gradlew :apps:spring-api:test --tests '*OdiiStoryWebBoundaryTests' --no-daemon`

Expected: 404 because no Odii controller is registered.

- [x] **Step 3: Implement controller and runtime configuration**

```java
@GetMapping("/api/v1/odii/stories/{storyId}")
ResponseEntity<?> detail(@PathVariable String storyId,
                         @RequestParam(defaultValue = "ko-KR") String language,
                         @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
                         HttpServletRequest request) {
    // Delegate and translate typed errors to the shared envelope.
}
```

- [x] **Step 4: Verify GREEN and response privacy**

Run: `./gradlew :apps:spring-api:test --tests '*OdiiStoryWebBoundaryTests' --no-daemon`

Expected: fixture-shaped list/detail, fallback, missing transcript, invalid cursor, unavailable snapshot, and 404 tests pass without provider IDs or secret URL parameters.

### Task 3: Transcript Status Contract Alignment

**Files:**
- Modify: `docs/contracts/openapi/r2-map-audio-insights.openapi.yaml`
- Modify: `docs/contracts/fixtures/r2/odii-story-detail-normal.json`
- Create: `docs/contracts/fixtures/r2/odii-story-detail-missing-transcript.json`
- Modify: `scripts/test/validate-r2-contract.py`

**Interfaces:**
- Consumes: Task 1 `OdiiTranscriptStatus`
- Produces: required OpenAPI `transcriptStatus` field and missing-transcript fixture

- [x] **Step 1: Tighten the validator first**

```python
if "transcriptStatus" not in schemas["OdiiStoryDetail"].get("required", []):
    fail("OdiiStoryDetail must require transcriptStatus")
```

- [x] **Step 2: Verify RED**

Run: `python3 scripts/test/validate-r2-contract.py`

Expected: failure because `OdiiStoryDetail` does not yet require `transcriptStatus`.

- [x] **Step 3: Add enum, required field, and fixtures**

```yaml
TranscriptStatus:
  type: string
  enum: [OFFICIAL, ESTIMATED, MISSING]
```

- [x] **Step 4: Verify GREEN**

Run: `bash scripts/verify-contracts`

Expected: R2 OpenAPI and all R2 fixtures validate.

### Task 4: Integration Verification and Delivery

**Files:**
- Modify after final `develop` merge only: `CHANGELOG.md`
- Modify after final `develop` merge only: `handoff.md`

**Interfaces:**
- Consumes: Tasks 1-3
- Produces: reviewable Issue #103 PR into `develop`

- [x] **Step 1: Run focused suites**

Run: `./gradlew :modules:audio:test :apps:spring-api:test --no-daemon`

- [x] **Step 2: Merge latest develop without rebasing**

Run: `git fetch origin develop && git merge --no-edit origin/develop`

- [x] **Step 3: Reconcile changelog and handoff, then run full verification**

Run: `./gradlew test --no-daemon && node --test scripts/test/*.test.mjs && node scripts/verify-planning-inputs.mjs && node scripts/validate-odii-fixtures.mjs && python3 -m pytest scripts/test/test_contract_validation.py && bash scripts/verify-contracts && (cd ai && uv run pytest && uv run ruff check && uv run mypy) && git diff --check && node scripts/print-branch-issue.mjs`

- [x] **Step 4: Commit, push, and create PR**

```bash
git commit -m "feat(audio): Odii 공개 조회 API 구현"
git push -u origin feature/103-odii-public-api
gh pr create --base develop --head feature/103-odii-public-api --title "feat(audio): Odii 공개 조회 API 구현" --body "## 관련 이슈

- Closes #103

## 변경 사항

- active Odii revision 기반 목록·상세 조회와 언어 fallback을 구현했습니다.
- transcript provenance와 공개 audio URL 정책을 적용했습니다.
- OpenAPI fixture와 runtime serializer를 함께 검증합니다."
```

### Task 5: Approved Canonical Place Query Integration

**Files:**
- Modify: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/query/OdiiStoryQueryService.java`
- Modify: `apps/spring-api/src/main/java/com/yrootlab/onmaru/web/audio/OdiiStoryConfiguration.java`
- Delete: `modules/audio/src/main/java/com/yrootlab/onmaru/audio/query/OdiiPlaceLinkResolver.java`
- Modify: Odii module and web boundary tests

- [x] **Step 1: Merge the #104 implementation from `origin/develop`**
- [x] **Step 2: Change tests to require `ApprovedAudioPlaceLinkQuery` and verify RED**
- [x] **Step 3: Replace the temporary resolver with the approved-link query**
- [x] **Step 4: Verify focused module and Spring web boundary tests**
