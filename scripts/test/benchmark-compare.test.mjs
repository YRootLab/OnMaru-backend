import test from 'node:test';
import assert from 'node:assert/strict';
import { chmod, mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { compatibilityReasons, normalizeComparison, runComparison, selectBaseline } from '../benchmark/toolkit-adapter.mjs';

const baseConfig = {
  suite: 'onmaru-release-smoke', environment: 'staging',
  runner: { architecture: 'linux-amd64', resource: { cpu: '2', memory: '4Gi' } },
  fixture: { name: 'onmaru-release-smoke', version: 'v1' }, dependencyMode: 'staging-readonly',
  warmupRuns: 2, repetitions: 3, minimumValidRuns: 3, cache: { cold: true, warm: true },
  stages: [{ name: 'spring-api' }, { name: 'fastapi' }],
  toolkit: { provenance: 'verified', version: 'fixture-1', timeoutMs: 1000, commands: {} },
  policy: { latencyRegressionThreshold: 0.05, improvementThreshold: 0.05 },
};
const baseline = { releaseId: 'v0.3.2', source: 'production-before-candidate', success: true, compatibility: { toolkitVersion: 'fixture-1' } };
const candidate = { releaseId: 'v0.4.0', compatibility: { toolkitVersion: 'fixture-1' } };

function result(latency = 90) {
  return { runIds: ['r1', 'r2', 'r3'], metrics: { latency_ms: { baseline: { median: 100, p95: 100 }, candidate: { median: latency, p95: latency } }, cpu_time_ms: { baseline: { median: 10 }, candidate: { median: 9 } } } };
}

test('selects baseline in required priority and fails closed without one', () => {
  assert.equal(selectBaseline([{ source: 'policy', success: true, releaseId: 'policy' }, { ...baseline, success: true }]).releaseId, baseline.releaseId);
  assert.equal(selectBaseline([{ source: 'policy', success: false }]), null);
});

test('normalizes compatible result and preserves deltas and run ids', () => {
  const normalized = normalizeComparison({ config: baseConfig, baseline, candidate, result: result(90) });
  assert.equal(normalized.status, 'improved', JSON.stringify(normalized));
  assert.equal(normalized.metrics.latency_ms.absoluteDelta, -10);
  assert.equal(normalized.metrics.latency_ms.relativeDelta, -0.1);
  assert.deepEqual(normalized.runIds, ['r1', 'r2', 'r3']);
});

test('normalizes compatibility mismatch as inconclusive', () => {
  const mismatch = { ...candidate, compatibility: { toolkitVersion: 'other' } };
  assert.deepEqual(compatibilityReasons(baseConfig, baseline, mismatch), ['toolkitVersion-mismatch']);
  const normalized = normalizeComparison({ config: baseConfig, baseline, candidate: mismatch, result: result() });
  assert.equal(normalized.status, 'inconclusive');
  assert.ok(normalized.reasons.includes('toolkitVersion-mismatch'));
});

test('unverified toolkit is fail-closed without executing a command', async () => {
  const config = { ...baseConfig, toolkit: { ...baseConfig.toolkit, provenance: 'unverified', commands: { compare: ['should-not-run'] } } };
  const normalized = await runComparison({ config, baseline, candidate });
  assert.equal(normalized.status, 'inconclusive');
  assert.ok(normalized.reasons.includes('toolkit-unverified'));
});

async function fakeToolkit(mode) {
  const dir = await mkdtemp(join(tmpdir(), 'onmaru-fake-toolkit-'));
  const executable = join(dir, 'fake-toolkit.mjs');
  const script = `#!/usr/bin/env node\nimport { readFile, writeFile } from 'node:fs/promises';\nconst input = JSON.parse(await readFile(process.argv[2], 'utf8'));\nif (!input.suite || !input.baseline.releaseId || !input.candidate.releaseId || input.environment !== 'staging' || input.conditions.repetitions !== 3 || input.conditions.cold !== true || input.conditions.warm !== true || input.stages.length !== 2) process.exit(7);\nif (${JSON.stringify(mode)} === 'timeout') await new Promise(() => {});\nif (${JSON.stringify(mode)} === 'cancel') await new Promise(() => {});\nif (${JSON.stringify(mode)} === 'missing') process.exit(0);\nif (${JSON.stringify(mode)} === 'mismatch') input.candidate.compatibility = { toolkitVersion: 'wrong' };\nawait writeFile(process.argv[3], JSON.stringify(${JSON.stringify(result(90))}));\n`;
  const timeoutLine = "if (" + JSON.stringify(mode) + " === 'timeout') await new Promise(() => {});";
  const stableTimeoutLine = "if (" + JSON.stringify(mode) + " === 'timeout') { setInterval(() => {}, 1000); await new Promise(() => {}); }";
  await writeFile(executable, script.replace(timeoutLine, stableTimeoutLine));
  await chmod(executable, 0o755);
  return { executable, dir };
}

test('invokes only configured fake executable with opaque input/output paths', async (t) => {
  const fake = await fakeToolkit('ok');
  t.after(async () => { await import('node:fs/promises').then(({ rm }) => rm(fake.dir, { recursive: true, force: true })); });
  const config = { ...baseConfig, toolkit: { ...baseConfig.toolkit, executable: fake.executable, commands: { compare: ['{input}', '{output}'] } } };
  const normalized = await runComparison({ config, baseline, candidate });
  assert.equal(normalized.status, 'improved');
});

for (const mode of ['timeout', 'missing']) {
  test(`normalizes ${mode} as inconclusive`, async (t) => {
    const fake = await fakeToolkit(mode);
    t.after(async () => { await import('node:fs/promises').then(({ rm }) => rm(fake.dir, { recursive: true, force: true })); });
    const timeoutMs = mode === 'missing' ? 1000 : 100;
    const config = { ...baseConfig, toolkit: { ...baseConfig.toolkit, timeoutMs, executable: fake.executable, commands: { compare: ['{input}', '{output}'] } } };
    const normalized = await runComparison({ config, baseline, candidate });
    assert.equal(normalized.status, 'inconclusive');
    const expectedReason = mode === 'missing' ? 'missing-artifact' : mode;
    assert.ok(normalized.reasons.includes(expectedReason));
    if (mode === 'timeout') assert.deepEqual(normalized.reasons, ['timeout', 'missing-metric', 'insufficient-valid-runs']);
  });
}

test('normalizes cancellation as inconclusive', async (t) => {
  const fake = await fakeToolkit('cancel');
  t.after(async () => { await import('node:fs/promises').then(({ rm }) => rm(fake.dir, { recursive: true, force: true })); });
  const config = { ...baseConfig, toolkit: { ...baseConfig.toolkit, timeoutMs: 1000, executable: fake.executable, commands: { compare: ['{input}', '{output}'] } } };
  const controller = new AbortController();
  setTimeout(() => controller.abort(), 20);
  const normalized = await runComparison({ config, baseline, candidate, signal: controller.signal });
  assert.equal(normalized.status, 'inconclusive');
  assert.ok(normalized.reasons.includes('cancellation'));
});
