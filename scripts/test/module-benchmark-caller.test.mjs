import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const workflowPath = '.github/workflows/module-benchmark.yml';
const toolkitSha = '7ecbb89aae771604d9c1c532cf123f239e279110';

test('shadow benchmark caller uses the pinned read-only Toolkit contract', async () => {
  const workflow = await readFile(workflowPath, 'utf8');

  assert.match(workflow, /^name: Module Benchmark$/m);
  assert.match(workflow, /^on:\n  push:\n    branches:\n      - develop\n    paths:\n/m);
  for (const path of [
    '.github/workflows/ci.yml',
    '.github/workflows/module-benchmark.yml',
    '.github/benchmark-modules.yml',
    'scripts/ci/**',
    'scripts/benchmark/**',
    'scripts/test/**',
    '**/src/test/**',
    'ai/tests/**',
    'build-logic/**',
    'gradle/**',
  ]) {
    assert.ok(workflow.includes(`      - '${path}'`), `missing benchmark trigger path: ${path}`);
  }
  assert.match(workflow, /^  workflow_dispatch:\n\npermissions:/m);
  assert.doesNotMatch(workflow, /^  pull_request:/m);
  assert.match(workflow, /^permissions:\n  contents: read$/m);
  assert.doesNotMatch(workflow, /secrets:\s*inherit|\b\w+: write\b|^\s*environment:/m);
  assert.match(workflow, new RegExp(`uses: YRootLab/OnMaru-backend-ci-toolkit/\\.github/workflows/module-benchmark\\.yml@${toolkitSha}`));
  assert.match(workflow, new RegExp(`^      toolkit_ref: ${toolkitSha}$`, 'm'));
  assert.match(workflow, /^      catalog_path: \.github\/benchmark-modules\.yml$/m);
  assert.match(workflow, /^      max_parallel: 4$/m);
  assert.match(workflow, /^      baseline_ref: develop$/m);
  assert.match(workflow, /^      comment_mode: none$/m);
  assert.match(workflow, /^      mode: develop$/m);
  assert.match(workflow, /^  summary:\n    needs: benchmark\n    if: always\(\)\n    runs-on: ubuntu-latest\n    permissions:\n      contents: read$/m);
  for (const output of ['result', 'comparison_id', 'manifest_uri', 'report_artifact', 'longest_module_duration_seconds', 'dag_critical_path_quality']) {
    assert.match(workflow, new RegExp(`needs\\.benchmark\\.outputs\\.${output}\\b`));
  }
  assert.doesNotMatch(workflow, /needs\.benchmark\.outputs\.critical_path_seconds/);
});
