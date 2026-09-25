import test from 'node:test';
import assert from 'node:assert/strict';
import { execFile as execFileCallback } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { compareCiBaselines, renderComparisonMarkdown } from '../benchmark/compare-ci-baseline.mjs';

const execFile = promisify(execFileCallback);

function baseline({ commitSha, wallClockMillis, workMillis = null, peakRssBytes = null, cacheState = 'unknown' }) {
  return {
    schemaVersion: 1,
    status: 'valid',
    suite: 'verify-serial',
    runCount: 3,
    identity: {
      commitSha,
      runnerImage: 'ubuntu-latest',
      dependencyMode: 'locked',
      cacheState,
      javaVersion: '21',
      pythonVersion: '3.12',
    },
    artifactUrls: ['https://github.com/YRootLab/OnMaru-backend/actions/runs/101'],
    metrics: {
      verifyWallClockMedianMillis: wallClockMillis,
      verifyWorkMedianMillis: workMillis,
      peakRssBytes,
    },
    resourceEvidence: workMillis === null ? 'unavailable-from-actions-api' : 'available',
  };
}

test('compares two compatible CI baselines without inventing unavailable resource metrics', () => {
  const comparison = compareCiBaselines({
    baseline: baseline({ commitSha: 'a'.repeat(40), wallClockMillis: 420_000 }),
    candidate: baseline({ commitSha: 'b'.repeat(40), wallClockMillis: 300_000 }),
  });

  assert.equal(comparison.status, 'improved');
  assert.deepEqual(comparison.metrics.verifyWallClockMedianMillis, {
    baseline: 420_000,
    candidate: 300_000,
    absoluteDelta: -120_000,
    relativeDelta: -0.285714,
  });
  assert.equal(comparison.metrics.verifyWorkMedianMillis, null);
  assert.equal(comparison.metrics.peakRssBytes, null);
  assert.equal(comparison.resourceEvidence, 'unavailable-from-actions-api');
});

test('rejects comparisons whose execution identities differ', () => {
  assert.throws(
    () => compareCiBaselines({
      baseline: baseline({ commitSha: 'a'.repeat(40), wallClockMillis: 420_000 }),
      candidate: baseline({ commitSha: 'b'.repeat(40), wallClockMillis: 300_000, cacheState: 'warm' }),
    }),
    /not comparable: cacheState/,
  );
});

test('renders a readable comparison with explicit evidence limitations', () => {
  const comparison = compareCiBaselines({
    baseline: baseline({ commitSha: 'a'.repeat(40), wallClockMillis: 420_000 }),
    candidate: baseline({ commitSha: 'b'.repeat(40), wallClockMillis: 300_000 }),
  });

  const markdown = renderComparisonMarkdown(comparison);
  assert.match(markdown, /CI 기준선 비교/);
  assert.match(markdown, /7분 0초/);
  assert.match(markdown, /5분 0초/);
  assert.match(markdown, /28\.57%/);
  assert.match(markdown, /GitHub Actions API만으로는 CPU·메모리 수치를 수집할 수 없습니다/);
});

test('writes deterministic JSON and Markdown files through the CLI', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'onmaru-ci-baseline-compare-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const before = baseline({ commitSha: 'a'.repeat(40), wallClockMillis: 420_000 });
  const after = baseline({ commitSha: 'b'.repeat(40), wallClockMillis: 300_000 });
  const beforePath = join(directory, 'before.json');
  const afterPath = join(directory, 'after.json');
  const jsonOutput = join(directory, 'comparison.json');
  const markdownOutput = join(directory, 'comparison.md');
  await Promise.all([
    writeFile(beforePath, JSON.stringify(before)),
    writeFile(afterPath, JSON.stringify(after)),
  ]);

  await execFile(process.execPath, [
    'scripts/benchmark/compare-ci-baseline.mjs', '--baseline', beforePath, '--candidate', afterPath,
    '--json-output', jsonOutput, '--markdown-output', markdownOutput,
  ]);

  assert.equal(JSON.parse(await readFile(jsonOutput, 'utf8')).status, 'improved');
  assert.match(await readFile(markdownOutput, 'utf8'), /CI 기준선 비교/);
});
