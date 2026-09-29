import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

test('issue 465 report contains evidence, visualization, and comparison limits', async () => {
  const report = await readFile('docs/reports/2026-09-29-issue-465-spring-api-shard-benchmark.md', 'utf8');
  for (const required of ['# ', 'inconclusive', '```mermaid', 'critical path', 'queue', 'CPU', 'RSS', 'failure', 'https://github.com/YRootLab/OnMaru-backend/actions/runs/']) {
    assert.match(report, new RegExp(required.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i'));
  }
  assert.doesNotMatch(report, /BEGIN (RSA|OPENSSH) PRIVATE KEY|ghp_[A-Za-z0-9_]+|raw log/i);
});
