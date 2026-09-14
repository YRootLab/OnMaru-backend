# Odii API Qualification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Odii API 실제 응답과 license 근거를 redacted fixture manifest로 고정해 Issue #61을 닫을 수 있게 한다.

**Architecture:** Node.js 표준 라이브러리 기반 capture script가 `.env.local`에서 secret을 읽고 redacted fixture와 manifest를 생성한다. 별도 validation module과 test가 hash 일치, 필수 시나리오, secret leakage 방지를 검증한다.

**Tech Stack:** Node.js ESM, `node:test`, SHA-256, JSON fixture, Markdown documentation.

## Global Constraints

- 한국어를 기본으로 사용한다.
- API key와 원문 민감 query를 repository-tracked 파일에 저장하지 않는다.
- `script`는 provider official transcript와 OnMaru estimated transcript를 구분한다.
- PR 본문은 `Closes #61`을 포함한다.

---

## File Structure

- Create `scripts/lib/odii-fixture-validation.mjs`: manifest와 fixture 검증 함수.
- Create `scripts/test/odii-fixture-validation.test.mjs`: validation unit tests.
- Create `scripts/validate-odii-fixtures.mjs`: repository fixture validation CLI.
- Create `scripts/capture-odii-fixtures.mjs`: live Odii capture CLI.
- Create `testing/fixtures/provider/odii/*.json`: redacted provider response fixtures.
- Create `docs/reference-snapshots/odii/manifest.json`: captured fixture metadata and hashes.
- Create `docs/reference-snapshots/odii/README.md`: qualification result and usage policy.

---

### Task 1: Fixture Validator

**Files:**
- Create: `scripts/lib/odii-fixture-validation.mjs`
- Create: `scripts/test/odii-fixture-validation.test.mjs`
- Create: `scripts/validate-odii-fixtures.mjs`

**Interfaces:**
- Produces: `validateOdiiFixtureSet({ rootDir, manifestPath, fixtureDir }) -> Promise<{ fixtureCount: number, scenarios: string[] }>`
- Produces: `assertNoSecretText(text, context) -> void`

- [ ] **Step 1: Write the failing test**

```js
import assert from 'node:assert/strict';
import { mkdtemp, rm, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { validateOdiiFixtureSet } from '../lib/odii-fixture-validation.mjs';

test('rejects manifest values that leak service keys', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'odii-fixtures-'));
  try {
    const fixtureDir = path.join(dir, 'fixtures');
    await mkdir(fixtureDir, { recursive: true });
    await writeFile(path.join(fixtureDir, 'normal.json'), '{}\n');
    await writeFile(path.join(dir, 'manifest.json'), JSON.stringify({
      requiredScenarios: ['normal'],
      captures: [{
        scenario: 'normal',
        fixture: 'normal.json',
        sha256: '44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a',
        endpoint: 'https://apis.data.go.kr/B551011/Odii/storyBasedList?serviceKey=abc',
      }],
    }));
    await assert.rejects(
      () => validateOdiiFixtureSet({ rootDir: dir, manifestPath: path.join(dir, 'manifest.json'), fixtureDir }),
      /secret-like value/,
    );
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test scripts/test/odii-fixture-validation.test.mjs`
Expected: FAIL with module not found for `odii-fixture-validation.mjs`.

- [ ] **Step 3: Write minimal implementation**

Implement JSON loading, SHA-256 calculation, required scenario checking, and redaction checks for `serviceKey=`, `ODII_API_KEY`, decoded key-like values, Kakao/Gemini variable names, and long URL-encoded secrets.

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test scripts/test/odii-fixture-validation.test.mjs`
Expected: PASS.

---

### Task 2: Capture Script

**Files:**
- Create: `scripts/capture-odii-fixtures.mjs`
- Modify: `scripts/test/odii-fixture-validation.test.mjs`

**Interfaces:**
- Consumes: `validateOdiiFixtureSet(...)`
- Produces CLI: `node scripts/capture-odii-fixtures.mjs`

- [ ] **Step 1: Write the failing test**

Add a test that calls exported `redactUrl('https://x.test/path?MobileOS=ETC&serviceKey=abc&_type=json')` and expects `https://x.test/path?MobileOS=ETC&_type=json`.

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test scripts/test/odii-fixture-validation.test.mjs`
Expected: FAIL because `redactUrl` is not exported.

- [ ] **Step 3: Write minimal implementation**

Create the capture script with `loadDotEnv`, `redactUrl`, `requestJson`, `writeFixture`, and scenario definitions for `story_based_first_page`, `story_based_second_page`, `story_location_based`, `story_search_hanok`, `empty_search`, `provider_error_missing_key`.

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test scripts/test/odii-fixture-validation.test.mjs`
Expected: PASS.

---

### Task 3: Live Capture And Documentation

**Files:**
- Create: `testing/fixtures/provider/odii/*.json`
- Create: `docs/reference-snapshots/odii/manifest.json`
- Create: `docs/reference-snapshots/odii/README.md`

**Interfaces:**
- Consumes CLI: `node scripts/capture-odii-fixtures.mjs`
- Consumes CLI: `node scripts/validate-odii-fixtures.mjs`

- [ ] **Step 1: Run capture**

Run: `node scripts/capture-odii-fixtures.mjs`
Expected: fixture JSON files and manifest are written without service keys.

- [ ] **Step 2: Validate fixtures**

Run: `node scripts/validate-odii-fixtures.mjs`
Expected: PASS with fixture count and scenario list.

- [ ] **Step 3: Write qualification README**

Document official data.go.kr evidence: free use, no use-scope restriction, development account 1,000 calls, operation review required for production account, languages ko/en/zh/ja stated by provider page.

- [ ] **Step 4: Run repository checks**

Run: `node --test scripts/test/odii-fixture-validation.test.mjs`
Run: `node scripts/validate-odii-fixtures.mjs`
Run: `./gradlew test`
Run: `cd ai && uv run pytest`
Expected: all pass.

---

## Self-Review

- Spec coverage: fixture 확보, language/script/audio mapping, error/quota/license 근거, redaction 검증을 tasks 1-3이 포함한다.
- Placeholder scan: no TBD/TODO placeholders remain.
- Type consistency: `validateOdiiFixtureSet` and `redactUrl` are defined before use.
