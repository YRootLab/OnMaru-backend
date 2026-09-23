import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
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
} = {}) {
  const evidenceDirectory = mkdtemp(join(tmpdir(), 'onmaru-pytest-profile-'));
  return evidenceDirectory.then(async (directory) => {
    const result = spawnSync(
      'python3',
      [
        profileScript,
        '--dry-run',
        '--evidence-dir',
        directory,
        ...(forceIsolationFailure ? ['--force-isolation-failure'] : []),
        ...(forceWorkerUnavailable ? ['--force-worker-unavailable'] : []),
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

test('pytest CI profile fixture runs before setup-uv and records a serial dry-run plan', async () => {
  const { result, evidence } = await runProfile();

  assert.equal(result.status, 0, result.stderr);
  assert.equal(evidence.selected_profile, 'serial');
  assert.equal(evidence.fallback_reason, 'workers-not-requested');
  assert.match(evidence.junit_xml, /pytest-junit\.xml$/);
  assert.ok(Number.isFinite(evidence.duration_ms));
  assert.equal(existsSync(evidence.junit_xml), false, 'dry run must not claim a generated JUnit XML file');
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
