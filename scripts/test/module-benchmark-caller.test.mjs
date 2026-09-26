import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const workflowPath = '.github/workflows/module-benchmark.yml';
const toolkitSha = 'ff3028ae728de076ea38aa56135529c1566f25a8';

test('shadow benchmark caller uses the pinned read-only Toolkit contract', async () => {
  const workflow = await readFile(workflowPath, 'utf8');

  assert.match(workflow, /^name: Module Benchmark$/m);
  assert.match(workflow, /^on:\n  pull_request:\n  push:\n    branches:\n      - develop\n  workflow_dispatch:\n\npermissions:/m);
  assert.match(workflow, /^permissions:\n  contents: read$/m);
  assert.doesNotMatch(workflow, /secrets:\s*inherit|\b\w+: write\b|^\s*environment:/m);
  assert.match(workflow, new RegExp(`uses: YRootLab/OnMaru-backend-ci-toolkit/\\.github/workflows/module-benchmark\\.yml@${toolkitSha}`));
  assert.match(workflow, new RegExp(`^      toolkit_ref: ${toolkitSha}$`, 'm'));
  assert.match(workflow, /^      catalog_path: \.github\/benchmark-modules\.yml$/m);
  assert.match(workflow, /^      max_parallel: 4$/m);
  assert.match(workflow, /^      baseline_ref: develop$/m);
  assert.match(workflow, /^      comment_mode: none$/m);
  assert.match(workflow, /^      mode: \$\{\{ github\.event_name == 'pull_request' && 'pr' \|\| 'develop' \}\}$/m);
  assert.match(workflow, /^  summary:\n    needs: benchmark\n    if: always\(\)\n    runs-on: ubuntu-latest\n    permissions:\n      contents: read$/m);
  for (const output of ['result', 'comparison_id', 'manifest_uri', 'report_artifact', 'critical_path_seconds']) {
    assert.match(workflow, new RegExp(`needs\\.benchmark\\.outputs\\.${output}\\b`));
  }
});
