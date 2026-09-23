import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const root = process.cwd();
const workflowPath = '.github/workflows/module-benchmark.yml';
const toolkitRef = 'a7c0b26b4a6c6cf405d5134610ba9c96e3f43a17';
const toolkitWorkflow = `YRootLab/OnMaru-modular-backend-pipeline-toolkit/.github/workflows/module-benchmark.yml@${toolkitRef}`;

function workflow() {
  return readFileSync(join(root, workflowPath), 'utf8');
}

test('module benchmark caller pins the toolkit and delegates affected planning to the consumer catalog', () => {
  assert.ok(existsSync(join(root, workflowPath)), 'module benchmark caller workflow must exist');
  const yaml = workflow();

  assert.match(yaml, new RegExp(toolkitWorkflow.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  assert.match(yaml, new RegExp(`toolkit_ref:\\s*${toolkitRef}`));
  assert.match(yaml, /catalog_path:\s*\.github\/benchmark-modules\.yml/);
  assert.match(yaml, /mode:\s*\$\{\{[^}]*pull_request[^}]*\}\}/);
  assert.match(yaml, /baseline_ref:\s*develop/);
  assert.match(yaml, /pull_request:/);
  assert.match(yaml, /push:\s*\n\s+branches:\s*\n\s+- develop/);
  assert.doesNotMatch(yaml, /paths-ignore:/);
  assert.match(yaml, /git diff --name-only|changed-path|affected/i);
});

test('module benchmark caller stays read-only and summarizes artifact-backed results without fork secrets', () => {
  const yaml = workflow();

  assert.match(yaml, /permissions:\s*\n\s+contents: read/);
  assert.doesNotMatch(yaml, /secrets:\s*inherit/);
  assert.doesNotMatch(yaml, /contents: write|pull-requests: write|packages: write|deployments: write/);
  assert.doesNotMatch(yaml, /environment:\s*(production|staging)|release|deploy/i);
  assert.match(yaml, /github\.event\.pull_request\.head\.repo\.fork|fork/i);
  assert.match(yaml, /GITHUB_STEP_SUMMARY/);
  assert.match(yaml, /report_artifact|manifest_uri|comparison_id/);
});
