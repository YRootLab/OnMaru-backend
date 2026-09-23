import assert from 'node:assert/strict';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const repositoryRoot = process.cwd();
const profileScript = join(repositoryRoot, 'ai/scripts/pytest-ci-profile.py');

function runProfile({
  workers,
  forceIsolationFailure = false,
  forceWorkerUnavailable = false,
  testTarget,
  dryRun = !testTarget,
} = {}) {
  const evidenceDirectory = mkdtemp(join(tmpdir(), 'onmaru-pytest-profile-'));
  return evidenceDirectory.then(async (directory) => {
    const result = spawnSync(
      'uv',
      [
        'run',
        '--project',
        'ai',
        'python',
        profileScript,
        ...(dryRun ? ['--dry-run'] : []),
        '--evidence-dir',
        directory,
        ...(forceIsolationFailure ? ['--force-isolation-failure'] : []),
        ...(forceWorkerUnavailable ? ['--force-worker-unavailable'] : []),
        ...(testTarget ? ['--', testTarget] : []),
      ],
      {
        cwd: repositoryRoot,
        encoding: 'utf8',
        env: {
          ...process.env,
          ...(workers === undefined ? {} : { ONMARU_PYTEST_WORKERS: workers }),
        },
      },
    );

    return {
      directory,
      result,
      evidence: JSON.parse(await readFile(join(directory, 'pytest-duration.json'), 'utf8')),
    };
  });
}

test('pytest CI profile stays serial unless workers are explicitly opted in and emits JUnit and duration evidence', async () => {
  const { result, evidence } = await runProfile({ testTarget: 'tests/test_health.py' });

  assert.equal(result.status, 0, result.stderr);
  assert.equal(evidence.selected_profile, 'serial');
  assert.equal(evidence.fallback_reason, 'workers-not-requested');
  assert.match(evidence.junit_xml, /pytest-junit\.xml$/);
  assert.ok(Number.isFinite(evidence.duration_ms));
  assert.match(await readFile(evidence.junit_xml, 'utf8'), /<testsuite/);
});

test('pytest CI profile reports a serial fallback when worker isolation fails', async () => {
  const { result, evidence } = await runProfile({
    workers: '2',
    forceIsolationFailure: true,
  });

  assert.equal(result.status, 0, result.stderr);
  assert.equal(evidence.selected_profile, 'serial');
  assert.equal(evidence.fallback_reason, 'isolation-check-failed');
  assert.equal(evidence.requested_workers, '2');
});

test('pytest CI profile reports a serial fallback when pytest-xdist is unavailable', async () => {
  const { result, evidence } = await runProfile({
    workers: 'auto',
    forceWorkerUnavailable: true,
  });

  assert.equal(result.status, 0, result.stderr);
  assert.equal(evidence.selected_profile, 'serial');
  assert.equal(evidence.fallback_reason, 'pytest-xdist-unavailable');
  assert.equal(evidence.requested_workers, 'auto');
});
