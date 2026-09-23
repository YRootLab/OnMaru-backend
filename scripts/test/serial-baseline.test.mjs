import test from 'node:test';
import assert from 'node:assert/strict';
import { createSerialBaseline } from '../benchmark/serial-baseline.mjs';

const identity = {
  commitSha: 'a'.repeat(40),
  runnerImage: 'ubuntu-24.04',
  dependencyMode: 'locked',
  cacheState: 'cold',
  javaVersion: '21',
  pythonVersion: '3.12',
};

function run(id, verifyDurationMillis) {
  return {
    runId: id,
    artifactUrl: `https://github.com/YRootLab/OnMaru-backend/actions/runs/${id}`,
    status: 'success',
    identity,
    commands: ['./gradlew :apps:spring-api:test --no-daemon', 'uv run pytest'],
    steps: [
      { name: 'Spring API tests', durationMillis: verifyDurationMillis, cpuMillis: 1200, maxRssBytes: 1024 },
      { name: 'FastAPI service quality gates', durationMillis: 500, cpuMillis: 400, maxRssBytes: 512 },
    ],
  };
}

test('creates a comparable baseline only from exactly three successful serial runs', () => {
  const baseline = createSerialBaseline({ suite: 'verify-serial', runs: [run('101', 1300), run('102', 1100), run('103', 1200)] });

  assert.equal(baseline.status, 'valid');
  assert.equal(baseline.runCount, 3);
  assert.equal(baseline.identity.commitSha, identity.commitSha);
  assert.equal(baseline.metrics.verifyWallClockMedianMillis, 1700);
  assert.deepEqual(baseline.artifactUrls, [
    'https://github.com/YRootLab/OnMaru-backend/actions/runs/101',
    'https://github.com/YRootLab/OnMaru-backend/actions/runs/102',
    'https://github.com/YRootLab/OnMaru-backend/actions/runs/103',
  ]);
});

test('rejects failed or non-comparable candidates instead of creating a baseline', () => {
  assert.throws(
    () => createSerialBaseline({ suite: 'verify-serial', runs: [run('101', 1300), { ...run('102', 1100), status: 'failure' }, run('103', 1200)] }),
    /must succeed/,
  );
  assert.throws(
    () => createSerialBaseline({ suite: 'verify-serial', runs: [run('101', 1300), { ...run('102', 1100), identity: { ...identity, cacheState: 'warm' } }, run('103', 1200)] }),
    /not comparable/,
  );
});
