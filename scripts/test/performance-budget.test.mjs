import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const root = resolve(__dirname, '../..');

test('performance budget and load scenarios validation', async (t) => {
  await t.test('verifies k6 load scenario files exist and contain thresholds', () => {
    const scenarios = [
      'hanok-search-load.js',
      'review-aggregation-load.js',
      'journey-sse-load.js',
      'odii-audio-load.js',
      'full-suite.js',
    ];

    for (const scenario of scenarios) {
      const path = resolve(root, 'testing/performance/k6/scenarios', scenario);
      assert.ok(existsSync(path), `Missing scenario file: ${scenario}`);
      const content = readFileSync(path, 'utf8');
      assert.ok(content.includes('thresholds'), `${scenario} must declare performance thresholds`);
      assert.ok(content.includes('p(95)'), `${scenario} must declare p95 latency budget`);
    }
  });

  await t.test('verifies review aggregation scenario enforces 2-second timeout threshold', () => {
    const path = resolve(root, 'testing/performance/k6/scenarios/review-aggregation-load.js');
    const content = readFileSync(path, 'utf8');
    assert.ok(content.includes('max<2000'), 'review aggregation scenario must enforce max<2000ms threshold');
  });

  await t.test('verifies EXPLAIN SQL benchmark plans exist and test required indexes', () => {
    const path = resolve(root, 'testing/performance/db/explain-plans.sql');
    assert.ok(existsSync(path), 'Missing explain-plans.sql');
    const content = readFileSync(path, 'utf8');
    assert.ok(content.includes('EXPLAIN'), 'explain-plans.sql must contain EXPLAIN statements');
    assert.ok(content.includes('visit_reviews'), 'explain-plans.sql must benchmark visit_reviews aggregation');
    assert.ok(content.includes('GROUP BY'), 'explain-plans.sql must test aggregation group by plan');
  });

  await t.test('verifies performance budget baseline documentation exists', () => {
    const docPath = resolve(root, 'docs/operations/performance/2026-09-performance-budget-baseline.md');
    assert.ok(existsSync(docPath), 'Missing performance budget baseline document');
    const content = readFileSync(docPath, 'utf8');
    assert.ok(content.includes('Performance Budget'), 'Document must contain Performance Budget table');
    assert.ok(content.includes('EXPLAIN ANALYZE'), 'Document must record EXPLAIN ANALYZE evidence');
    assert.ok(content.includes('HikariCP'), 'Document must define connection pool budget');
  });
});
