import test from 'node:test';
import assert from 'node:assert/strict';
import { createReleaseEvidence, lookupBaseline, promotionDecision, resolveReleaseTag, selectPreviousSuccessfulRelease, validateDigestPair } from '../benchmark/workflow-support.mjs';

const sha = 'a'.repeat(40);
const digest = 'sha256:' + 'b'.repeat(64);

test('resolves tag input from tag push or dispatch and rejects non-release refs', () => {
  assert.equal(resolveReleaseTag({ refName: 'refs/tags/v1.2.3', eventName: 'push' }), 'v1.2.3');
  assert.equal(resolveReleaseTag({ tag: 'v1.2.3', eventName: 'workflow_dispatch' }), 'v1.2.3');
  assert.throws(() => resolveReleaseTag({ refName: 'refs/heads/develop', eventName: 'workflow_dispatch' }), /release tag/);
});

test('validates expected and deployed digests before evidence creation', () => {
  assert.equal(validateDigestPair(digest, digest, 'spring-api'), true);
  assert.throws(() => validateDigestPair(digest, 'sha256:' + 'c'.repeat(64), 'spring-api'), /mismatch/);
  const release = createReleaseEvidence({
    tag: 'v1.2.3', commitSha: sha, workflowRunId: '42',
    expectedDigests: { 'spring-api': digest, 'ai-service': digest },
    deployedDigests: { 'spring-api': digest, 'ai-service': digest },
  });
  assert.equal(release.services.length, 2);
});

test('selects the most recent successful previous release and excludes candidate', () => {
  const selected = selectPreviousSuccessfulRelease([
    { tagName: 'v1.2.3', benchmarkStatus: 'improved', publishedAt: '2026-09-20T00:00:00Z' },
    { tagName: 'v1.2.4', benchmarkStatus: 'inconclusive', publishedAt: '2026-09-22T00:00:00Z' },
    { tagName: 'v1.2.2', benchmarkStatus: 'unchanged', publishedAt: '2026-09-19T00:00:00Z' },
  ], 'v1.2.3');
  assert.equal(selected.tagName, 'v1.2.2');
  assert.equal(selectPreviousSuccessfulRelease([], 'v1.2.3'), null);
});

test('maps GitHub release records to a successful baseline and fails closed without status evidence', () => {
  const selected = lookupBaseline([
    { tag_name: 'v1.2.3', published_at: '2026-09-20T00:00:00Z', body: 'Benchmark status: improved' },
    { tag_name: 'v1.2.4', published_at: '2026-09-22T00:00:00Z', body: 'Benchmark status: inconclusive' },
  ], 'v1.2.4');
  assert.equal(selected.releaseId, 'v1.2.3');
  assert.deepEqual(lookupBaseline([{ tag_name: 'v1.2.3', body: 'no evidence' }], 'v1.2.4'), {});
});

test('promotion policy passes only improved/unchanged and fail-closes inconclusive', () => {
  assert.deepEqual(promotionDecision('improved'), { status: 'improved', outcome: 'pass', autoPromotion: true, approvalRequired: false, warning: null });
  assert.equal(promotionDecision('unchanged').autoPromotion, true);
  assert.equal(promotionDecision('regressed').approvalRequired, true);
  assert.equal(promotionDecision('inconclusive').autoPromotion, false);
});

test('workflow YAML structure preserves ordering, least privilege and fork guard', async () => {
  const fs = await import('node:fs/promises');
  const yaml = await fs.readFile('.github/workflows/benchmark-release.yml', 'utf8');
  for (const required of ['workflow_dispatch:', 'tags:', 'packages: write', 'contents: read', 'concurrency:', 'timeout-minutes:', 'github.event.repository.fork', 'staging-readiness', 'baseline-lookup', 'comparison', 'regression-approval', 'benchmark-promotion', 'upload-artifact@v4', 'GITHUB_STEP_SUMMARY']) assert.match(yaml, new RegExp(required.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  assert.ok(yaml.indexOf('staging-readiness:') < yaml.indexOf('baseline-lookup:'));
  assert.ok(yaml.indexOf('baseline-lookup:') < yaml.indexOf('comparison:'));
  assert.ok(yaml.indexOf('expected-deployed-digest') < yaml.indexOf('comparison:'));
  assert.doesNotMatch(yaml, /pull_request:/);
});
