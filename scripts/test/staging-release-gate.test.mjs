import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";

const REPO_ROOT = process.cwd();
const RELEASE_GATE_MANIFEST = path.join(REPO_ROOT, "testing/release-gate/staging-release-gate.json");
const SIGNOFF_DOC = path.join(REPO_ROOT, "docs/operations/release-evidence/staging-acceptance-signoff.md");
const EVIDENCE_README = path.join(REPO_ROOT, "docs/operations/release-evidence/README.md");

test("staging release gate and operational acceptance validation", async (t) => {
  await t.test("verifies staging release gate manifest exists and defines 9 required gates", () => {
    assert.ok(fs.existsSync(RELEASE_GATE_MANIFEST), "staging-release-gate.json must exist");

    const manifest = JSON.parse(fs.readFileSync(RELEASE_GATE_MANIFEST, "utf8"));
    assert.equal(manifest.version, "1.0.0");
    assert.ok(Array.isArray(manifest.gates), "gates array must be present");
    assert.equal(manifest.gates.length, 9, "all 9 operational gates must be defined");

    const requiredGateIds = [
      "GATE-01-R1-CATALOG",
      "GATE-02-R2-MEDIA-SAVED",
      "GATE-03-R3-JOURNEY-AI",
      "GATE-04-SECURITY-SECRET",
      "GATE-05-DATA-PERSISTENCE-RESTORE",
      "GATE-06-LOAD-PERFORMANCE",
      "GATE-07-CHAOS-RESILIENCE",
      "GATE-08-OBSERVABILITY-ALERT",
      "GATE-09-DEPLOY-ROLLBACK",
    ];

    const presentIds = manifest.gates.map((g) => g.id);
    for (const reqId of requiredGateIds) {
      assert.ok(presentIds.includes(reqId), `gate ${reqId} must be present`);
    }
  });

  await t.test("verifies every gate points to existing evidence file and verification command", () => {
    const manifest = JSON.parse(fs.readFileSync(RELEASE_GATE_MANIFEST, "utf8"));

    for (const gate of manifest.gates) {
      assert.equal(gate.status, "PASSED", `gate ${gate.id} status must be PASSED`);
      assert.ok(gate.verificationCommand, `gate ${gate.id} must have a verification command`);

      const evidencePath = path.join(REPO_ROOT, gate.evidencePath);
      assert.ok(fs.existsSync(evidencePath), `evidence file for ${gate.id} must exist at ${gate.evidencePath}`);
    }
  });

  await t.test("verifies staging acceptance signoff documentation is complete and approved", () => {
    assert.ok(fs.existsSync(SIGNOFF_DOC), "staging-acceptance-signoff.md must exist");

    const signoffContent = fs.readFileSync(SIGNOFF_DOC, "utf8");
    assert.ok(signoffContent.includes("PROCEED TO PRODUCTION"), "signoff must declare PROCEED TO PRODUCTION");
    assert.ok(signoffContent.includes("9 / 9 (100% PASSED)"), "signoff must show 100% pass rate");
    assert.ok(signoffContent.includes("Backend Architecture Lead"), "signoff must have Architecture approval");
    assert.ok(signoffContent.includes("Reliability & DevOps Lead"), "signoff must have DevOps approval");
  });

  await t.test("verifies release evidence README indexes all core tracks", () => {
    assert.ok(fs.existsSync(EVIDENCE_README), "release-evidence README must exist");

    const readmeContent = fs.readFileSync(EVIDENCE_README, "utf8");
    assert.ok(readmeContent.includes("staging-acceptance-signoff.md"));
    assert.ok(readmeContent.includes("r1/README.md"));
    assert.ok(readmeContent.includes("r2/README.md"));
    assert.ok(readmeContent.includes("journey/README.md"));
    assert.ok(readmeContent.includes("grafana-o02-checklist.md"));
  });
});
