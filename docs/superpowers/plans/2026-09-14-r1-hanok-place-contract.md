# R1 Hanok Place Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Issue #62의 R1 한옥·장소·찜 OpenAPI와 fixture를 동결한다.

**Architecture:** Endpoint 구현 없이 `docs/contracts/openapi/r1.openapi.yaml`과 `docs/contracts/fixtures/r1/*.json`만 public contract로 추가한다. 검증 스크립트가 표준 OpenAPI 검증, JSON Schema 기반 fixture body 검증, endpoint/status별 fixture schema 연결, 필수 path/schema, fixture coverage, provider key 비노출, 공유 `placeId`를 확인한다.

**Tech Stack:** OpenAPI 3.1 YAML, JSON fixture, Python 3 validation script with PyYAML, openapi-spec-validator, jsonschema.

## Global Constraints

- Public schema에 `contentId`, `pageNo`, provider `key`, `serviceKey`를 노출하지 않는다.
- 한옥/지도/Odii 카드는 같은 canonical `placeId`를 사용한다.
- Endpoint code는 #62 범위 밖이다.
- PR 본문은 이 Issue를 완료하므로 `Closes #62`를 사용한다.

---

### Task 1: Contract Validator

**Files:**
- Create: `scripts/test/validate-r1-contract.py`
- Create: `scripts/test/requirements-r1-contract.txt`

**Interfaces:**
- Produces: `python3 scripts/test/validate-r1-contract.py` command.

- [x] **Step 1: Write the failing test**

검증 스크립트는 `docs/contracts/openapi/r1.openapi.yaml`와 `docs/contracts/fixtures/r1`이 없으면 실패한다.

- [x] **Step 2: Run test to verify it fails**

Run: `python3 -m pip install -r scripts/test/requirements-r1-contract.txt && python3 scripts/test/validate-r1-contract.py`
Expected: FAIL with missing OpenAPI contract.

### Task 2: OpenAPI Contract

**Files:**
- Create: `docs/contracts/openapi/r1.openapi.yaml`
- Modify: `docs/contracts/README.md`

**Interfaces:**
- Consumes: validator from Task 1.
- Produces: OpenAPI schemas referenced by fixtures.

- [x] **Step 1: Create OpenAPI YAML**

필수 path는 `/hanoks`, `/hanoks/monthly`, `/hanoks/{placeId}`, `/places/{placeId}`, `/saved-resources/places/{placeId}`, `/saved-resources`, `/me/timeline`이다.

- [x] **Step 2: Run validator**

Run: `python3 scripts/test/validate-r1-contract.py`
Expected: FAIL with missing fixture directory.

### Task 3: R1 Fixtures

**Files:**
- Create: `docs/contracts/fixtures/r1/*.json`

**Interfaces:**
- Consumes: OpenAPI schema names from Task 2.
- Produces: machine-readable examples for normal, empty, 404, cursor, auth, unavailable cases.

- [x] **Step 1: Add fixture files**

Fixture names must include `hanok-list-normal`, `hanok-list-empty`, `hanok-list-cursor`, `hanok-monthly-normal`, `hanok-detail-normal`, `place-detail-normal`, `saved-place-auth-required`, `saved-place-normal`, `saved-resources-cursor`, `timeline-normal`, `timeline-unavailable`, `place-not-found`, and `service-unavailable`.

- [x] **Step 2: Run validator**

Run: `python3 scripts/test/validate-r1-contract.py`
Expected: PASS.

### Task 4: Repository Verification

**Files:**
- No additional files.

**Interfaces:**
- Consumes: completed docs and fixtures.
- Produces: final verification result for PR body.

- [x] **Step 1: Run hygiene and app checks**

Run: `git diff --check`, `python3 scripts/test/validate-r1-contract.py`, `./gradlew test`, and `cd ai && uv run pytest`.

- [x] **Step 2: Summarize PR**

PR body should include `Closes #62` and validator/test results.
