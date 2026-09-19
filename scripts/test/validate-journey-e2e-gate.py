#!/usr/bin/env python3
"""Validate the executable Journey release-gate manifest and its owned evidence."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = ROOT / "testing/e2e/journey/contract-gate.json"
EVIDENCE_PATH = ROOT / "docs/operations/release-evidence/journey/README.md"
RUNTIME_SUITE_PATH = ROOT / "apps/spring-api/src/test/java/com/yrootlab/onmaru/journey/JourneyContractE2ETests.java"
REQUIRED_CONTRACTS = {
    "docs/contracts/openapi/journey.openapi.yaml",
    "docs/contracts/schemas/journey-sse-event.schema.json",
}
REQUIRED_COVERAGE = {
    "clarification",
    "normal-board",
    "guest-intake",
    "member-intake",
    "serializer-drift",
    "proposal",
    "pin-action",
    "exclude-action",
    "state-version-increment",
    "stale-version-conflict",
    "idempotency-replay",
    "idempotency-conflict",
    "sse-stage",
    "sse-terminal",
    "sse-replay",
    "sse-reset",
    "sse-auth-closed",
    "sse-heartbeat",
    "run-cancel",
    "cancel-race",
    "active-slot-release",
    "deadline-sweeper",
    "ai-down-fallback",
    "degraded-baseline",
    "daily-quota-limit",
    "rate-limit-retry-after",
    "saved-journey-create",
    "saved-journey-list-cursor",
    "saved-journey-detail",
    "saved-journey-delete",
    "saved-journey-resume",
    "unavailable-refs",
    "owner-isolation",
}


def fail(message: str) -> None:
    raise AssertionError(message)


def main() -> None:
    if not MANIFEST_PATH.is_file():
        fail(f"missing Journey E2E gate manifest: {MANIFEST_PATH.relative_to(ROOT)}")

    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != "1.0":
        fail("Journey E2E gate schemaVersion must be 1.0")
    if manifest.get("runtimeSuite") != "com.yrootlab.onmaru.journey.JourneyContractE2ETests":
        fail("Journey E2E gate must name the canonical Spring runtime suite")
    if not RUNTIME_SUITE_PATH.is_file():
        fail(f"missing Journey runtime suite: {RUNTIME_SUITE_PATH.relative_to(ROOT)}")

    contracts = set(manifest.get("contracts", []))
    missing_contracts = REQUIRED_CONTRACTS - contracts
    if missing_contracts:
        fail(f"Journey E2E gate is missing contracts: {sorted(missing_contracts)}")
    for relative_path in contracts:
        if not (ROOT / relative_path).is_file():
            fail(f"Journey E2E gate references missing contract: {relative_path}")

    fixture_groups = manifest.get("fixtureGroups", [])
    for relative_path in fixture_groups:
        fixture_dir = ROOT / relative_path
        if not fixture_dir.is_dir() or not any(fixture_dir.glob("*.json")):
            fail(f"Journey E2E gate references empty fixture group: {relative_path}")

    scenarios = manifest.get("scenarios", [])
    scenario_ids = [scenario.get("id") for scenario in scenarios]
    if len(scenario_ids) != len(set(scenario_ids)):
        fail("Journey E2E gate scenario ids must be unique")
    actual_coverage = {
        coverage
        for scenario in scenarios
        for coverage in scenario.get("covers", [])
    }
    missing_coverage = REQUIRED_COVERAGE - actual_coverage
    if missing_coverage:
        fail(f"Journey E2E gate is missing coverage: {sorted(missing_coverage)}")

    if not EVIDENCE_PATH.is_file():
        fail(f"missing Journey release evidence: {EVIDENCE_PATH.relative_to(ROOT)}")
    evidence = EVIDENCE_PATH.read_text(encoding="utf-8")
    for scenario_id in scenario_ids:
        if f"`{scenario_id}`" not in evidence:
            fail(f"Journey release evidence is missing scenario {scenario_id}")

    print(f"validated Journey E2E gate: {len(scenarios)} runtime scenarios")


if __name__ == "__main__":
    main()
