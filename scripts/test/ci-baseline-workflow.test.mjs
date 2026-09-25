import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

test('baseline collection workflow reads completed CI runs without logs or elevated write permissions', async () => {
  const workflow = await readFile('.github/workflows/collect-ci-baseline.yml', 'utf8');
  assert.match(workflow, /workflow_dispatch:/);
  assert.match(workflow, /actions: read/);
  assert.match(workflow, /contents: read/);
  assert.match(workflow, /run_ids:/);
  assert.match(workflow, /gh api "repos\/\$\{\{ github\.repository \}\}\/actions\/runs\/\$run_id"/);
  assert.match(workflow, /ci-run-collector\.mjs/);
  assert.match(workflow, /serial-baseline\.mjs/);
  assert.match(workflow, /actions\/upload-artifact@v4/);
  assert.doesNotMatch(workflow, /secrets\./);
});
