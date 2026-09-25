import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

test('CI supports manual runs so three executions of one develop commit can form a baseline', async () => {
  const workflow = await readFile('.github/workflows/ci.yml', 'utf8');
  assert.match(workflow, /workflow_dispatch:/);
  assert.match(workflow, /push:[\s\S]+?develop/);
});
