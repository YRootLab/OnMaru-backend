# Staging Pagination Fixtures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand the isolated Lightsail staging dataset to 100 map places, 65 public warmth reviews, and 65 public Odii stories so the existing FE staging mode can exercise three cursor pages and map zoom aggregation without FE changes.

**Architecture:** Keep the four hand-written connected fixtures as canonical detail scenarios and add deterministic PostgreSQL `generate_series` rows in the existing transactional seed. Extend the PostgreSQL integration test to validate ownership ranges, idempotency, spatial/category distribution, and the keyset ordering that the public APIs consume; keep the runtime API contracts unchanged.

**Tech Stack:** PostgreSQL 17/PostGIS 3.5, Flyway, Spring Boot/JDBC, JUnit 5, AssertJ, Testcontainers, Node.js contract tests, Docker Compose

## Global Constraints

- Work is tracked by GitHub Issue #675 on branch `feature/675-staging-pagination-fixtures`.
- fixture 소유 공개 데이터는 지도 장소 100건, 온기 후기 65건, Odii story 65건이다. 사용자가 추가한 공개 후기는 보존되므로 온기 API 전체 합계와 페이지 수가 증가할 수 있다.
- 추가 공개 사용자 후기가 없는 기준 상태에서 `limit=30`은 30/30/5와 중복·누락 없음, 마지막 null cursor를 반환한다. 사용자 후기가 있는 DB에서는 반환 cursor를 끝까지 순회하고 fixture 65개와 추가 사용자 데이터를 구분한다.
- Generated places must span at least four regions, multiple coordinate clusters, and the `SPOT`, `CAFE`, and `MARKET` canonical categories while preserving the existing connected HANOK scenarios.
- Existing public IDs such as `p-staging-hanok-a` remain stable.
- The seed remains transactional, idempotent, and restricted to the `onmaru_staging` database.
- 정리는 #675 소유 합성 namespace의 projection·오디오 row에 한정한다. 후기와 장소 identity·public ID는 upsert하고 현재 생성 집합 밖의 합성 후기는 HIDDEN으로 전환해 좋아요·신고·audit 참조를 보존한다. 수작업 fixture와 사용자 데이터는 삭제하지 않는다.
- Do not change frontend code, production data, production configuration, public cursor formats, or API default limits.

---

### Task 1: Lock fixture volume, distribution, and idempotency in PostgreSQL tests

**Files:**
- Modify: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/StagingFixtureTests.java`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/StagingFixtureTests.java`

**Interfaces:**
- Consumes: `infra/lightsail/staging/seed.sql` through the existing `executeSeedForTestDatabase()` helper.
- Produces: executable assertions for exact public counts, four-region distribution, category coverage, stable keyset order, hidden-review exclusion, and repeat-seed idempotency.

- [ ] **Step 1: Replace the old small-fixture count assertions with exact target assertions**

Add assertions scoped to revision `54500000-0000-4000-8000-000000000010` rather than relying on unscoped table totals:

```java
assertThat(longValue(connection, """
        SELECT count(*) FROM onmaru.map_place_read_projection
        WHERE revision_id = '54500000-0000-4000-8000-000000000010'
        """)).isEqualTo(100);
assertThat(longValue(connection, """
        SELECT count(*) FROM onmaru.community_visit_reviews
        WHERE status = 'PUBLISHED' AND id::text LIKE '54500675-%'
           OR status = 'PUBLISHED' AND id IN (
             '54500000-0000-4000-8000-000000000111',
             '54500000-0000-4000-8000-000000000112')
        """)).isEqualTo(65);
assertThat(longValue(connection, """
        SELECT count(*) FROM onmaru.audio_story_versions
        WHERE revision_id = '54500000-0000-4000-8000-000000000011'
          AND status = 'ACTIVE'
        """)).isEqualTo(65);
```

Use parentheses around the review predicate in the implementation so operator precedence cannot admit a non-public generated review.

- [ ] **Step 2: Add distribution and projection integrity assertions**

Assert that the generated map data is meaningful for FE map testing:

```java
assertThat(longValue(connection, """
        SELECT count(DISTINCT sido_code)
        FROM onmaru.map_place_read_projection
        WHERE revision_id = '54500000-0000-4000-8000-000000000010'
        """)).isGreaterThanOrEqualTo(4);
assertThat(value(connection, """
        SELECT string_agg(canonical_category || ':' || count_value, ',' ORDER BY canonical_category)
        FROM (
          SELECT canonical_category, count(*)::text AS count_value
          FROM onmaru.map_place_category_projection
          WHERE revision_id = '54500000-0000-4000-8000-000000000010'
          GROUP BY canonical_category
        ) counts
        """)).contains("CAFE:", "MARKET:", "SPOT:");
assertThat(longValue(connection, """
        SELECT row_count FROM onmaru.map_projection_publications
        WHERE revision_id = '54500000-0000-4000-8000-000000000010'
          AND projection_name = 'map_place_read_projection'
        """)).isEqualTo(100);
```

- [ ] **Step 3: 실제 공개 controller와 JDBC 경로의 cursor·지도 응답을 검증한다**

seeded PostgreSQL에 실제 JDBC store/query service를 연결하고 MockMvc로 공개 controller를 호출한다. Odii는 production `OdiiStoryConfiguration`의 signed cursor와 staging `samplelib.com` 공개 host 정책을 사용한다. 응답 `nextCursor`를 다음 요청에 그대로 전달해 전역 후기와 story의 `[30, 30, 5]`, `totalCount=65`, `hasMore=[true,true,false]`, 마지막 null cursor, 공개 ID 집합 일치와 중복 없음을 확인한다. SQL로 cursor나 hasMore를 복제하지 않는다.

기준 장소 `p-staging-hanok-a`의 후기 `[30,1]`과 숨김 제외를 확인한다. Odii의 실제 정렬은 `source_modified_at DESC, identity.public_id ASC`이며 동률 cursor는 public ID가 큰 다음 행을 읽는다. 같은 timestamp를 가진 62개 생성 story가 페이지 경계를 넘는 회귀 사례로 이 순서를 보호한다.

지도 목록은 SPOT/CAFE/MARKET별 34/33/33과 합계 100개 public ID를 검증한다. 각 카테고리의 전국 bbox·zoom 1/6/9/11로 PLACE/CLUSTER/DISTRICT/REGION 응답과 합산 count·COMPLETE coverage를 확인한다. 각 카테고리는 PLACE 기본 limit 60 이내이며, 전체 100개를 한 viewport에서 요청하면 CLUSTER fallback이 가능하다.

범위 밖 PUBLISHED fixture 후기와 사용자 좋아요·신고·audit를 삽입한 뒤 반복 seed로 공개 fixture 65건에 수렴하고 참조가 보존되는지 확인한다. namespace 밖 공개 사용자 후기 26개가 있는 경우 공개 합계 91과 `[30,30,30,1]`을 확인하고 fixture 65개와 사용자 26개를 구분한다.

- [ ] **Step 4: Add a numeric SQL helper and run the focused test to see the expected failure**

```java
private long longValue(java.sql.Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
        rows.next();
        return rows.getLong(1);
    }
}
```

Run:

```bash
./gradlew :apps:spring-api:test --tests '*StagingFixtureTests' --no-daemon
```

Expected: FAIL because the current seed has only 4 map places, 2 public reviews, and 3 active stories.

---

### Task 2: Generate deterministic 100/65/65 staging fixtures

**Files:**
- Modify: `infra/lightsail/staging/seed.sql`
- Test: `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/StagingFixtureTests.java`

**Interfaces:**
- Consumes: existing catalog/audio/community tables created by Flyway and the canonical revisions `...0010` and `...0011`.
- Produces: #675-owned IDs prefixed by UUID text `54500675-`, public place IDs `p-staging-generated-001` through `p-staging-generated-096`, and deterministic timestamps/coordinates derived from series indexes.

- [ ] **Step 1: Add four synthetic regions and a generated-row ownership convention**

Keep the existing `STG` region and add deterministic region pairs for Seoul, Jeonju, Gyeongju, and Busan fixture clusters. Use codes beginning with `STG-` so they cannot collide with provider codes. Document in SQL comments that `54500675-*` is owned exclusively by Issue #675.

Use a region CTE shaped like:

```sql
WITH regions(ord, sido_id, district_id, sido_code, district_code, sido_name, district_name,
             base_lng, base_lat) AS (VALUES
  (0, '54500675-0000-4000-8100-000000000001'::uuid,
      '54500675-0000-4000-8200-000000000001'::uuid,
      'STG-SEOUL', 'STG-SEOUL-01', '합성 서울권', '합성 서울지구', 126.9780, 37.5665),
  (1, '54500675-0000-4000-8100-000000000002'::uuid,
      '54500675-0000-4000-8200-000000000002'::uuid,
      'STG-JEONJU', 'STG-JEONJU-01', '합성 전주권', '합성 전주지구', 127.1480, 35.8242),
  (2, '54500675-0000-4000-8100-000000000003'::uuid,
      '54500675-0000-4000-8200-000000000003'::uuid,
      'STG-GYEONGJU', 'STG-GYEONGJU-01', '합성 경주권', '합성 경주지구', 129.2247, 35.8562),
  (3, '54500675-0000-4000-8100-000000000004'::uuid,
      '54500675-0000-4000-8200-000000000004'::uuid,
      'STG-BUSAN', 'STG-BUSAN-01', '합성 부산권', '합성 부산지구', 129.0756, 35.1796)
)
```

- [ ] **Step 2: 생성 projection을 정리하고 사용자 후기 참조를 보존한다**

`54500675-*`의 audio links/tags/subtitles/versions/stories/spots, map category/read projection, catalog images/tags/versions/sources와 generated region만 자식부터 정리한다. 후기·좋아요·신고·audit, 장소 identity와 public ID는 삭제하지 않는다. 현재 생성 후기 집합을 만든 뒤 해당 집합 밖의 #675 소유 PUBLISHED 후기를 HIDDEN으로 전환하고 현재 집합은 upsert한다. `54500000-*`와 namespace 밖 사용자 후기에는 이 정리 predicate를 적용하지 않는다.

```sql
UPDATE onmaru.community_visit_reviews existing
SET status = 'HIDDEN'
WHERE existing.id::text LIKE '54500675-%'
  AND existing.status = 'PUBLISHED'
  AND NOT EXISTS (
    SELECT 1 FROM staging_fixture_reviews current_fixture
    WHERE current_fixture.review_id = existing.id
  );
```

- [ ] **Step 3: Insert 96 generated catalog and map places**

Use `generate_series(1, 96)` joined to the four-region CTE. Derive the region with `(n - 1) % 4`, category with `(n - 1) % 3`, and a small grid offset around each base coordinate:

```sql
base_lng + (((n - 1) / 4) % 6) * 0.0025,
base_lat + (((n - 1) / 24) % 4) * 0.0025
```

Insert matching rows into `catalog_place_identity`, `catalog_place_sources`, `catalog_place_public_ids`, `catalog_place_versions`, `map_place_read_projection`, and `map_place_category_projection`. Use `lpad(n::text, 3, '0')` in public IDs and sort keys. Add images only when `n % 3 <> 0` so FE fallback rendering remains covered.

- [ ] **Step 4: Rebuild map counts and publication metadata from generated rows**

Replace hard-coded count rows with grouped INSERT queries over `map_place_read_projection` joined to `map_place_category_projection`. Write both REGION and DISTRICT scopes, then update publication `row_count` from `count(*)` and compute a stable checksum from ordered public IDs:

```sql
SELECT encode(digest(string_agg(public_id, ',' ORDER BY public_id), 'sha256'), 'hex')
FROM onmaru.map_place_read_projection
WHERE revision_id = '54500000-0000-4000-8000-000000000010'
```

- [ ] **Step 5: Insert 63 generated public reviews**

Create deterministic member IDs if needed, then generate review IDs in the `54500675-*` range. Assign the first 29 generated reviews to `p-staging-hanok-a` so its two existing reviews plus generated rows yield at least 31 public reviews; distribute the rest across generated places. Use unique fixed timestamps descending from `2026-10-06T12:00:00Z`, status `PUBLISHED`, and synthetic text/tags.

- [ ] **Step 6: Insert 62 generated public Odii stories**

Create enough generated spots and matching story rows in `audio_odii_spots`, `audio_odii_stories`, and `audio_story_versions`. Set every generated version to `ACTIVE`, language `ko`, deterministic `source_modified_at`, three- or six-second public sample URLs, and synthetic titles/scripts. Keep the existing three story IDs and place links unchanged.

- [ ] **Step 7: Run the focused integration test until all volume and ordering assertions pass**

Run:

```bash
./gradlew :apps:spring-api:test --tests '*StagingFixtureTests' --no-daemon
```

Expected: PASS; the second seed execution keeps counts at 100/65/65 and restores drifted canonical values.

- [ ] **Step 8: Commit the test and seed together**

```bash
git add apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/StagingFixtureTests.java infra/lightsail/staging/seed.sql
git commit -m "feat(staging): pagination 검증 fixture 확장"
```

---

### Task 3: Document the FE staging workflow and protect fixture targets in deploy contracts

**Files:**
- Modify: `infra/lightsail/staging/README.md`
- Modify: `docs/operations/staging-fe-guide.md`
- Modify: `scripts/test/deploy-pipeline.test.mjs`
- Test: `scripts/test/deploy-pipeline.test.mjs`

**Interfaces:**
- Consumes: exact fixture counts and stable public IDs produced by Task 2.
- Produces: operator/FE instructions for starting staging and exercising three pages, plus a lightweight contract that prevents the documented counts and safety wording from drifting unnoticed.

- [ ] **Step 1: Write failing documentation contract assertions**

Add a Node test that checks both documentation files mention the exact counts, `limit=30`, and the existing SSH start command:

```javascript
it('documents pagination-sized staging fixtures for FE verification', () => {
  const readme = read('infra/lightsail/staging/README.md');
  const feGuide = read('docs/operations/staging-fe-guide.md');

  for (const document of [readme, feGuide]) {
    assert.match(document, /지도 장소 100건/);
    assert.match(document, /공개 온기 후기 65건/);
    assert.match(document, /Odii story 65건/);
    assert.match(document, /limit=30/);
  }
  assert.match(feGuide, /onmaru-staging-operator@13\.125\.191\.16 start/);
});
```

- [ ] **Step 2: Run the Node test and verify it fails on the old 4/3/3 documentation**

Run:

```bash
node --test scripts/test/deploy-pipeline.test.mjs
```

Expected: FAIL because the current documents do not describe the 100/65/65 fixture.

- [ ] **Step 3: Update operator and FE documentation**

Replace the old fixture count paragraph with exact 100/65/65 counts. Document this flow without changing FE code:

```text
1. SSH start로 온디맨드 스테이징을 기동한다.
2. 기존 FE staging mode에서 staging-api.onmaru.site를 사용한다.
3. 지도 권역 이동과 줌 변경으로 place/cluster/district/region 응답을 확인한다.
4. 온기 및 Odii 목록을 limit=30으로 세 페이지 순회해 30/30/5와 종료 상태를 확인한다.
5. 검증 후 SSH stop으로 스테이징을 중지한다.
```

State explicitly that all rows are synthetic, the seed does not copy production members/sessions, and repeated starts do not multiply fixture rows.

- [ ] **Step 4: Run documentation/deploy tests**

Run:

```bash
node --test scripts/test/deploy-pipeline.test.mjs
```

Expected: PASS.

- [ ] **Step 5: Run repository verification**

Run:

```bash
docker compose --env-file infra/lightsail/staging/.env.example -f infra/lightsail/staging/compose.yaml config --quiet
bash scripts/verify-contracts
node --test scripts/test/*.test.mjs
git diff --check
node scripts/print-branch-issue.mjs
```

Expected: Compose and contract validation succeed, all Node tests pass, `git diff --check` prints nothing, and the branch parser prints `675`.

- [ ] **Step 6: Commit documentation and contract changes**

```bash
git add infra/lightsail/staging/README.md docs/operations/staging-fe-guide.md scripts/test/deploy-pipeline.test.mjs
git commit -m "docs(staging): FE pagination 검증 절차 추가"
```

---

### Task 4: Prepare PR evidence and defer live Lightsail completion until merge

**Files:**
- Modify: `handoff.md`
- Verify only: all files changed by Tasks 1–3

**Interfaces:**
- Consumes: local verification results and commits from Tasks 1–3.
- Produces: PR-ready verification evidence and a precise post-merge staging runbook; it does not mutate the live server from the feature branch.

- [ ] **Step 1: Record the current-session handoff**

Add Issue #675, branch name, touched files, exact verification commands/results, and the post-merge live steps to `handoff.md`. State that live Lightsail deployment requires a merged, verified `develop` SHA.

- [ ] **Step 2: Review the complete diff against the design**

Run:

```bash
git diff origin/develop...HEAD --stat
git diff origin/develop...HEAD -- infra/lightsail/staging/seed.sql apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/StagingFixtureTests.java
git diff --check origin/develop...HEAD
```

Expected: only Issue #675 files are present; generated rows are isolated to `54500675-*`; no whitespace errors.

- [ ] **Step 3: Commit the handoff**

```bash
git add handoff.md
git commit -m "docs(staging): fixture 확장 검증 기록"
```

- [ ] **Step 4: Push and open a PR to develop**

PR 생성 직전에 Issue·변경 파일·work log·검증 결과를 대조한다. 한국어 PR 본문은 `Refs #675`를 사용하고 #675의 Closes/Fixes/Resolves 연결이 없는지 확인한다. 스테이징 배포는 검증된 병합 `develop` ref가 필요하므로 실제 Lightsail 검증은 병합 후 진행한다. 병합만으로 #675를 닫지 않고 실제 배포·공개 API·운영 health 전후·staging stop 증적과 acceptance criteria를 확인할 때까지 OPEN으로 유지한다.

- [ ] **Step 5: Post-merge live verification**

After merge, dispatch `deploy.yml` on `develop` with `deploy_staging=true`, start staging through the restricted SSH operator, and query the public APIs through all three pages. Record workflow URL, merged SHA, image digest, page sizes, unique ID counts, map zoom/category results, and production health before/after in Issue #675. Stop staging after verification. Close the Issue only when those acceptance criteria match the merged PR and live evidence.
