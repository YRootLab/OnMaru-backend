import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer, get } from 'node:http';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  createManifest,
  createReleaseEvidence,
  promotionDecision,
  renderJobSummary,
  resolveReleaseTag,
  validateDigestPair,
} from '../benchmark/workflow-support.mjs';
import { normalizeComparison, runComparison } from '../benchmark/toolkit-adapter.mjs';

const commitSha = 'a'.repeat(40);
const digest = 'sha256:' + 'b'.repeat(64);
const changedDigest = 'sha256:' + 'c'.repeat(64);

const config = {
  suite: 'onmaru-release-smoke',
  environment: 'staging',
  runner: { architecture: 'linux-amd64', resource: { cpu: '2', memory: '4Gi' } },
  fixture: { name: 'onmaru-release-smoke', version: 'v1' },
  dependencyMode: 'staging-readonly',
  warmupRuns: 2,
  repetitions: 3,
  minimumValidRuns: 3,
  cache: { cold: true, warm: true },
  stages: [
    { name: 'spring-api', readinessPath: '/actuator/health' },
    { name: 'fastapi', readinessPath: '/ready' },
  ],
  toolkit: { provenance: 'verified', version: 'fixture-1', timeoutMs: 200, commands: {} },
  policy: { latencyRegressionThreshold: 0.05, improvementThreshold: 0.05 },
};

const baseline = {
  releaseId: 'v1.2.3',
  source: 'production-before-candidate',
  success: true,
  compatibility: { toolkitVersion: 'fixture-1' },
  services: [{ name: 'spring-api' }, { name: 'ai-service' }],
};

function metricResult(candidateLatency = 90) {
  return {
    benchmarkRunId: 'run-fixture-001',
    runIds: ['baseline-1', 'candidate-1', 'candidate-2'],
    metrics: {
      latency_ms: {
        baseline: { median: 100, p95: 110 },
        candidate: { median: candidateLatency, p95: candidateLatency + 5 },
      },
      cpu_time_ms: { baseline: { median: 10 }, candidate: { median: 9 } },
    },
  };
}

async function fakeToolkit(mode = 'improved') {
  const directory = await mkdtemp(join(tmpdir(), 'onmaru-w4-toolkit-'));
  const executable = join(directory, 'fake-toolkit.mjs');
  const script = `#!/usr/bin/env node
import { readFile, writeFile } from 'node:fs/promises';
const input = JSON.parse(await readFile(process.argv[2], 'utf8'));
if (input.suite !== 'onmaru-release-smoke' || input.environment !== 'staging' || input.conditions.repetitions !== 3) process.exit(7);
if (${JSON.stringify(mode)} === 'timeout' || ${JSON.stringify(mode)} === 'cancellation') await new Promise(() => {});
if (${JSON.stringify(mode)} === 'missing-artifact') process.exit(0);
if (${JSON.stringify(mode)} === 'invalid-data') { await writeFile(process.argv[3], '{invalid-json'); process.exit(0); }
await writeFile(process.argv[3], JSON.stringify(${JSON.stringify(metricResult(mode === 'regressed' ? 120 : mode === 'unchanged' ? 102 : 90))}));
`;
  await writeFile(executable, script);
  return { directory, executable };
}

async function readinessFixture(t, failed = false) {
  const server = createServer((request, response) => {
    const healthy = !failed && (request.url === '/actuator/health' || request.url === '/ready');
    response.statusCode = healthy ? 200 : 503;
    response.end(healthy ? 'ok' : 'unavailable');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());
  const { port } = server.address();
  return async (path) => new Promise((resolve, reject) => {
    get({ hostname: '127.0.0.1', port, path }, (response) => {
      response.resume();
      response.on('end', () => resolve(response.statusCode === 200));
    }).on('error', reject);
  });
}

test('verifies release tag → metadata → digest → readiness → compare → evidence links', async (t) => {
  const releaseTag = resolveReleaseTag({ refName: 'refs/tags/v1.2.4', eventName: 'push' });
  const release = createReleaseEvidence({
    tag: releaseTag,
    commitSha,
    workflowRunId: 'fixture-run-001',
    expectedDigests: { 'spring-api': digest, 'ai-service': digest },
    deployedDigests: { 'spring-api': digest, 'ai-service': digest },
  });
  assert.equal(validateDigestPair(digest, digest, 'spring-api'), true);

  const ready = await readinessFixture(t);
  assert.equal(await ready('/actuator/health'), true);
  assert.equal(await ready('/ready'), true);

  const fake = await fakeToolkit('improved');
  t.after(() => rm(fake.directory, { recursive: true, force: true }));
  const comparison = await runComparison({
    config: { ...config, toolkit: { ...config.toolkit, executable: process.execPath, commands: { compare: [fake.executable, '{input}', '{output}'] } } },
    baseline,
    candidate: release,
  });
  assert.equal(comparison.status, 'improved', JSON.stringify(comparison));
  assert.deepEqual(comparison.runIds, ['baseline-1', 'candidate-1', 'candidate-2']);

  const manifest = await createManifest({
    release,
    baseline,
    comparison,
    config,
    runId: comparison.benchmarkRunId,
  });
  assert.equal(manifest.releaseId, release.releaseId);
  assert.equal(manifest.imageDigests['spring-api'], digest);
  assert.equal(manifest.rawUri, 'benchmark/raw/run-fixture-001.json');
  assert.equal(manifest.normalizedUri, 'benchmark/normalized/run-fixture-001.json');
  assert.equal(manifest.reportUri, 'benchmark/reports/run-fixture-001.json');

  const decision = promotionDecision(comparison.status);
  const summary = renderJobSummary({ release, baseline, comparison, decision });
  assert.match(summary, /Status: \*\*improved\*\*/);
  assert.match(summary, /Run ID: `run-fixture-001`/);
  assert.equal(decision.outcome, 'pass');
});

test('keeps failed readiness and digest mismatch out of regression status', async (t) => {
  const ready = await readinessFixture(t, true);
  assert.equal(await ready('/ready'), false);
  assert.throws(() => validateDigestPair(digest, changedDigest, 'spring-api'), /mismatch/);

  const failedRelease = () => createReleaseEvidence({
    tag: 'v1.2.4', commitSha, workflowRunId: 'fixture-run-002',
    expectedDigests: { 'spring-api': digest, 'ai-service': digest },
    deployedDigests: { 'spring-api': changedDigest, 'ai-service': digest },
  });
  assert.throws(failedRelease, /mismatch/);
  assert.notEqual(promotionDecision('inconclusive').status, 'regressed');
});

for (const mode of ['missing-artifact', 'timeout', 'cancellation', 'invalid-data']) {
  test(`normalizes ${mode} as inconclusive, never regressed`, async (t) => {
    const fake = await fakeToolkit(mode);
    t.after(() => rm(fake.directory, { recursive: true, force: true }));
    const controller = mode === 'cancellation' ? new AbortController() : undefined;
    if (controller) setTimeout(() => controller.abort(), 20);
    const comparison = await runComparison({
      config: { ...config, toolkit: { ...config.toolkit, executable: process.execPath, commands: { compare: [fake.executable, '{input}', '{output}'] } } },
      baseline,
      candidate: { releaseId: 'v1.2.4', compatibility: { toolkitVersion: 'fixture-1' } },
      signal: controller?.signal,
    });
    assert.equal(comparison.status, 'inconclusive', JSON.stringify(comparison));
    assert.notEqual(comparison.status, 'regressed');
    assert.ok(comparison.reasons.length > 0);
  });
}

test('allows regressed only for a compatible, valid metric comparison', () => {
  const comparison = normalizeComparison({
    config,
    baseline,
    candidate: { releaseId: 'v1.2.4', compatibility: { toolkitVersion: 'fixture-1' } },
    result: metricResult(120),
  });
  assert.equal(comparison.status, 'regressed');
  assert.equal(promotionDecision(comparison.status).outcome, 'warning');
  assert.equal(promotionDecision(comparison.status).approvalRequired, true);
});
