import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeCiRun } from '../benchmark/ci-run-collector.mjs';

const sha = 'a'.repeat(40);
const identity = {
  runnerImage: 'ubuntu-latest', dependencyMode: 'locked', cacheState: 'unknown', javaVersion: '21', pythonVersion: '3.12',
};

test('normalizes a successful GitHub Actions CI run into serial baseline evidence without inventing resource metrics', () => {
  const evidence = normalizeCiRun({
    run: { id: 123, conclusion: 'success', head_sha: sha, html_url: 'https://github.com/YRootLab/OnMaru-backend/actions/runs/123' },
    jobs: { jobs: [{ name: 'verify', conclusion: 'success', steps: [
      { name: 'Checkout', conclusion: 'success', started_at: '2026-09-25T00:00:00Z', completed_at: '2026-09-25T00:00:05Z' },
      { name: 'Spring API tests', conclusion: 'success', started_at: '2026-09-25T00:00:05Z', completed_at: '2026-09-25T00:02:05Z' },
    ] }] },
    identity,
    commands: ['./gradlew :apps:spring-api:test --no-daemon'],
  });

  assert.equal(evidence.runId, '123');
  assert.equal(evidence.identity.commitSha, sha);
  assert.equal(evidence.steps[1].durationMillis, 120000);
  assert.equal(evidence.steps[1].cpuMillis, null);
  assert.equal(evidence.steps[1].maxRssBytes, null);
  assert.equal(evidence.resourceEvidence, 'unavailable-from-actions-api');
});

test('rejects non-successful runs and malformed job timestamps', () => {
  assert.throws(() => normalizeCiRun({ run: { id: 1, conclusion: 'failure', head_sha: sha, html_url: 'https://github.com/YRootLab/OnMaru-backend/actions/runs/1' }, jobs: { jobs: [] }, identity, commands: ['x'] }), /must succeed/);
  assert.throws(() => normalizeCiRun({ run: { id: 1, conclusion: 'success', head_sha: sha, html_url: 'https://github.com/YRootLab/OnMaru-backend/actions/runs/1' }, jobs: { jobs: [{ name: 'verify', conclusion: 'success', steps: [{ name: 'bad', conclusion: 'success' }] }] }, identity, commands: ['x'] }), /timestamps/);
});
