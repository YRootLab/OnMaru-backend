import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const contracts = await readFile('docs/benchmark/contracts.md', 'utf8');
const evidence = await readFile('docs/operations/release-evidence/benchmark.md', 'utf8');
const readme = await readFile('docs/benchmark/README.md', 'utf8');
const evidenceIndex = await readFile('docs/operations/release-evidence/README.md', 'utf8');
const workflow = await readFile('.github/workflows/benchmark-release.yml', 'utf8');

test('documents the W4 release-to-release evidence contract', () => {
  for (const field of ['schemaVersion', 'releaseId', 'tag', 'commitSha', 'environment', 'workflowRunId', 'services', 'expectedDigest', 'deployedDigest', 'readinessPath']) {
    assert.match(contracts, new RegExp(`\\b${field}\\b`));
  }
  for (const field of ['benchmarkRunId', 'baselineReleaseId', 'candidateReleaseId', 'imageDigests', 'configSha256', 'rawUri', 'normalizedUri', 'reportUri']) {
    assert.match(contracts, new RegExp(`\\b${field}\\b`));
  }
  for (const status of ['improved', 'unchanged', 'regressed', 'inconclusive']) assert.match(evidence, new RegExp(`\\b${status}\\b`));
  for (const reason of ['missing artifact', 'failed deployment', 'timeout', 'cancellation', 'invalid data', 'digest mismatch']) assert.match(evidence, new RegExp(reason, 'i'));
});

test('documents toolkit boundary, redaction, summary, links, and reproducibility', () => {
  for (const command of ['release register', 'benchmark suite', 'compare', 'publish']) assert.match(`${evidence}\n${contracts}`, new RegExp(`\\b${command.replace(' ', '\\s+')}\\b`));
  for (const phrase of ['Job Summary', 'artifact link', 'redaction', 'Known limitations', '재현 명령']) assert.match(evidence, new RegExp(phrase, 'i'));
  assert.match(readme, /benchmark\.md/);
  assert.match(evidenceIndex, /benchmark\.md/);
});

test('keeps the workflow DAG and evidence publication observable', () => {
  for (const job of ['staging-readiness:', 'baseline-lookup:', 'comparison:', 'regression-approval:', 'promotion-gate:']) assert.ok(workflow.includes(job));
  assert.match(workflow, /baseline-lookup:[\s\S]+?needs: \[build-and-scan, staging-readiness\]/);
  assert.match(workflow, /comparison:[\s\S]+?needs: \[build-and-scan, staging-readiness, baseline-lookup\]/);
  assert.match(workflow, /regression-approval:[\s\S]+?needs: \[build-and-scan, comparison\]/);
  assert.match(workflow, /promotion-gate:[\s\S]+?needs: \[build-and-scan, comparison, regression-approval\]/);
  for (const required of ['GITHUB_STEP_SUMMARY', 'actions/upload-artifact@v4', 'benchmark/raw', 'benchmark/normalized', 'benchmark/reports', 'manifest.json', 'gate.json']) assert.match(workflow, new RegExp(required.replace(/[.*+?^${}()|[\\]\\]/g, '\\$&')));
  assert.match(workflow, /continue-on-error: true/);
});
