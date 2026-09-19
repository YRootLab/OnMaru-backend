import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";

const REPO_ROOT = process.cwd();
const MANIFEST_PATH = path.join(REPO_ROOT, "testing/chaos/rehearsal-manifest.json");
const RUNBOOK_PATH = path.join(REPO_ROOT, "docs/operations/runbooks/degraded-mode.md");

test("chaos resilience rehearsal suite verification", async (t) => {
  await t.test("verifies rehearsal manifest exists and defines all required scenarios", () => {
    assert.ok(fs.existsSync(MANIFEST_PATH), "rehearsal-manifest.json must exist");

    const manifest = JSON.parse(fs.readFileSync(MANIFEST_PATH, "utf8"));
    assert.equal(manifest.version, "1.0.0");
    assert.ok(Array.isArray(manifest.rehearsals), "rehearsals array must be present");
    assert.ok(manifest.rehearsals.length >= 5, "at least 5 chaos rehearsal scenarios required");

    const requiredScenarios = [
      "CHAOS-001", // TourAPI LKG
      "CHAOS-002", // Odii Degraded
      "CHAOS-003", // FastAPI Baseline Fallback
      "CHAOS-004", // SSE Replay & Snapshot
      "CHAOS-005", // Late Callback Stale Write 0
    ];

    const presentIds = manifest.rehearsals.map((r) => r.id);
    for (const reqId of requiredScenarios) {
      assert.ok(presentIds.includes(reqId), `scenario ${reqId} must be defined in manifest`);
    }
  });

  await t.test("verifies all referenced scenario json files exist with valid schema and assertions", () => {
    const manifest = JSON.parse(fs.readFileSync(MANIFEST_PATH, "utf8"));

    for (const rehearsal of manifest.rehearsals) {
      const scenarioFilePath = path.join(REPO_ROOT, rehearsal.scenarioFile);
      assert.ok(fs.existsSync(scenarioFilePath), `scenario file ${rehearsal.scenarioFile} must exist`);

      const scenario = JSON.parse(fs.readFileSync(scenarioFilePath, "utf8"));
      assert.equal(scenario.scenarioId, rehearsal.id, "scenario ID must match manifest");
      assert.ok(scenario.title, "scenario title must be present");
      assert.ok(Array.isArray(scenario.faultInjections), "faultInjections array must be defined");
      assert.ok(Array.isArray(scenario.verificationAssertions), "verificationAssertions array must be defined");
    }
  });

  await t.test("verifies SLA thresholds and zero stale write invariant", () => {
    const manifest = JSON.parse(fs.readFileSync(MANIFEST_PATH, "utf8"));

    const staleDefense = manifest.rehearsals.find((r) => r.id === "CHAOS-005");
    assert.ok(staleDefense, "CHAOS-005 must exist");
    assert.equal(staleDefense.slaThresholds.staleWritesPermitted, 0, "stale writes must be strictly 0");

    const baselineFallback = manifest.rehearsals.find((r) => r.id === "CHAOS-003");
    assert.ok(baselineFallback, "CHAOS-003 must exist");
    assert.equal(baselineFallback.slaThresholds.fallbackSuccessRate, "100%", "fallback success rate must be 100%");
    assert.ok(baselineFallback.slaThresholds.minimumPlaceCount >= 3, "fallback must provide at least 3 places");
  });

  await t.test("verifies degraded mode runbook documentation exists and matches manifest alerting metrics", () => {
    assert.ok(fs.existsSync(RUNBOOK_PATH), "degraded-mode.md runbook must exist");

    const runbookContent = fs.readFileSync(RUNBOOK_PATH, "utf8");
    assert.ok(runbookContent.includes("Graceful Degradation"), "runbook must cover Graceful Degradation");
    assert.ok(runbookContent.includes("Zero Stale Writes"), "runbook must cover Zero Stale Writes");
    assert.ok(runbookContent.includes("Deterministic Baseline"), "runbook must document Deterministic Baseline");
    assert.ok(runbookContent.includes("Last-Event-ID"), "runbook must document SSE Last-Event-ID replay");

    const manifest = JSON.parse(fs.readFileSync(MANIFEST_PATH, "utf8"));
    for (const rehearsal of manifest.rehearsals) {
      for (const metric of rehearsal.alertMetrics) {
        assert.ok(
          runbookContent.includes(metric),
          `runbook must mention alert metric ${metric} from ${rehearsal.id}`
        );
      }
    }
  });
});
