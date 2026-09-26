import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';

const verifierPath = 'scripts/ci/verify-fan-in.mjs';
const workflowPath = '.github/workflows/ci.yml';
const toolkitSha = 'ff3028ae728de076ea38aa56135529c1566f25a8';

const validEnvironment = {
  HYGIENE_RESULT: 'success',
  CONTRACT_RESULT: 'success',
  MODULE_JOB_RESULT: 'success',
  TOOLKIT_RESULT: 'inconclusive',
  MANIFEST_URI: 'https://github.com/YRootLab/OnMaru-backend/actions/runs/123',
  REPORT_ARTIFACT: 'module-benchmark-report',
};

function runFanIn(overrides = {}) {
  return spawnSync(process.execPath, [verifierPath], {
    encoding: 'utf8',
    env: { ...validEnvironment, ...overrides },
  });
}

test('accepts complete successful fan-in evidence', () => {
  const result = runFanIn();
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /required fan-in passed/);
});

for (const [name, override] of [
  ['hygiene failure', { HYGIENE_RESULT: 'failure' }],
  ['contract cancellation', { CONTRACT_RESULT: 'cancelled' }],
  ['module skip', { MODULE_JOB_RESULT: 'skipped' }],
  ['toolkit failure', { TOOLKIT_RESULT: 'failed' }],
  ['missing toolkit result', { TOOLKIT_RESULT: '' }],
  ['missing manifest URI', { MANIFEST_URI: '' }],
  ['foreign manifest URI', { MANIFEST_URI: 'https://example.com/actions/runs/123' }],
  ['missing report artifact', { REPORT_ARTIFACT: '' }],
]) {
  test(`rejects ${name}`, () => {
    const result = runFanIn(override);
    assert.notEqual(result.status, 0);
  });
}

test('CI workflow separates required lanes and fails closed through final verify', async () => {
  const workflow = await readFile(workflowPath, 'utf8');

  assert.match(workflow, /^permissions:\n  contents: read$/m);
  for (const job of ['hygiene', 'contract', 'module-tests', 'verify']) {
    assert.match(workflow, new RegExp(`^  ${job}:$`, 'm'));
  }
  assert.match(workflow, new RegExp(`uses: YRootLab/OnMaru-backend-ci-toolkit/\\.github/workflows/module-benchmark\\.yml@${toolkitSha}`));
  assert.match(workflow, new RegExp(`^      toolkit_ref: ${toolkitSha}$`, 'm'));
  assert.match(workflow, /^      catalog_path: \.github\/benchmark-modules\.yml$/m);
  assert.match(workflow, /^      max_parallel: 4$/m);
  assert.match(workflow, /^      baseline_ref: develop$/m);
  assert.match(workflow, /^      comment_mode: none$/m);
  assert.match(workflow, /^      mode: \$\{\{ github\.event_name == 'pull_request' && 'pr' \|\| 'develop' \}\}$/m);
  assert.match(workflow, /^    needs: \[hygiene, contract, module-tests\]$/m);
  assert.match(workflow, /^    if: \$\{\{ always\(\) \}\}$/m);
  assert.match(workflow, /^          HYGIENE_RESULT: \$\{\{ needs\.hygiene\.result \}\}$/m);
  assert.match(workflow, /^          CONTRACT_RESULT: \$\{\{ needs\.contract\.result \}\}$/m);
  assert.match(workflow, /^          MODULE_JOB_RESULT: \$\{\{ needs\.module-tests\.result \}\}$/m);
  assert.match(workflow, /^          TOOLKIT_RESULT: \$\{\{ needs\.module-tests\.outputs\.result \}\}$/m);
  assert.match(workflow, /^          MANIFEST_URI: \$\{\{ needs\.module-tests\.outputs\.manifest_uri \}\}$/m);
  assert.match(workflow, /^          REPORT_ARTIFACT: \$\{\{ needs\.module-tests\.outputs\.report_artifact \}\}$/m);
  assert.match(workflow, /node scripts\/ci\/verify-fan-in\.mjs/);
  assert.doesNotMatch(workflow, /secrets:\s*inherit|\b\w+: write\b|^\s*environment:/m);
});
