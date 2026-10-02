import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createReleaseEvidence, lookupBaseline, promotionDecision, resolveReleaseTag, selectPreviousSuccessfulRelease, validateDigestPair } from '../benchmark/workflow-support.mjs';

const sha = 'a'.repeat(40);
const digest = 'sha256:' + 'b'.repeat(64);

test('resolves tag input from tag push or dispatch and rejects non-release refs', () => {
  assert.equal(resolveReleaseTag({ refName: 'refs/tags/v1.2.3', eventName: 'push' }), 'v1.2.3');
  assert.equal(resolveReleaseTag({ tag: 'v1.2.3', eventName: 'workflow_dispatch' }), 'v1.2.3');
  assert.throws(() => resolveReleaseTag({ refName: 'refs/heads/develop', eventName: 'workflow_dispatch' }), /release tag/);
});

test('dispatch checkout resolves the release tag namespace rather than an ambiguous branch name', async () => {
  const yaml = await readFile('.github/workflows/benchmark-release.yml', 'utf8');
  const build = yaml.split('\n  build-and-scan:')[1].split('\n  migration-gate:')[0];
  assert.match(build, /ref: \$\{\{ inputs\.release_tag && format\('refs\/tags\/\{0\}', inputs\.release_tag\) \|\| github\.ref \}\}/);
});

test('release input shell rejects a same-name branch HEAD and accepts the annotated tag commit', async () => {
  const yaml = await readFile('.github/workflows/benchmark-release.yml', 'utf8');
  const step = yaml.split('      - id: release-input\n')[1].split('      - uses:')[0];
  const shell = step.split('        run: |\n')[1].split('\n').map((line) => line.replace(/^          /, '')).join('\n');
  const directory = await mkdtemp(join(tmpdir(), 'release-ref-'));
  const outputPath = join(directory, 'github-output');
  const git = (...args) => {
    const result = spawnSync('git', ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', ...args], {
      cwd: directory, encoding: 'utf8',
    });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  };
  const run = () => spawnSync('bash', ['--noprofile', '--norc', '-eo', 'pipefail', '-c', shell], {
    cwd: directory, encoding: 'utf8', env: { ...process.env, TAG: 'v1.2.4', GITHUB_OUTPUT: outputPath },
  });
  try {
    git('init', '-q');
    git('commit', '--allow-empty', '-qm', 'release commit');
    const releaseSha = git('rev-parse', 'HEAD');
    git('tag', '-a', 'v1.2.4', '-m', 'release tag');
    git('checkout', '-qb', 'v1.2.4');
    git('commit', '--allow-empty', '-qm', 'different branch commit');
    await writeFile(outputPath, '');
    assert.notEqual(run().status, 0, 'same-name branch must not become release evidence');
    assert.equal(await readFile(outputPath, 'utf8'), '', 'invalid identity must publish no outputs');
    git('checkout', '-q', '--detach', 'refs/tags/v1.2.4');
    assert.equal(run().status, 0);
    assert.equal(await readFile(outputPath, 'utf8'), `release-tag=v1.2.4\ncommit-sha=${releaseSha}\n`);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
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
  const migration = yaml.split('\n  migration-gate:')[1].split('\n  staging-readiness:')[0];
  assert.match(migration, /ref: \$\{\{ needs\.build-and-scan\.outputs\.commit-sha \}\}/);
});

test('workflow preserves reusable trend evidence through a pinned toolkit contract', async () => {
  const fs = await import('node:fs/promises');
  const yaml = await fs.readFile('.github/workflows/benchmark-release.yml', 'utf8');
  const pinnedRef = '08501bf55a373e27782c89cca348040fa1affa93';
  assert.match(yaml, /trend-manifest\.json/);
  assert.match(yaml, /trend-manifest-\$\{\{ needs\.build-and-scan\.outputs\.release-tag \}\}/);
  assert.match(yaml, new RegExp(`YRootLab/OnMaru-backend-ci-toolkit/.github/workflows/reusable-benchmark.yml@${pinnedRef}`));
  assert.match(yaml, new RegExp(`toolkit-ref: ${pinnedRef}`));
  assert.match(yaml, /candidate-artifact-name: trend-manifest-\$\{\{ needs\.build-and-scan\.outputs\.release-tag \}\}/);
  assert.match(yaml, /history-release-tags: \$\{\{ needs\.baseline-lookup\.outputs\.baseline-tag \}\}/);
  assert.match(yaml, /config-hash: \$\{\{ steps\.gate\.outputs\.config-hash \}\}/);
  assert.match(yaml, /gh release view "\$RELEASE_TAG"/);
  assert.match(yaml, /gh release upload "\$RELEASE_TAG" .*trend-manifest\.json/);
});

test('only the canonical module result owns release approval and W4 remains diagnostic', async () => {
  const fs = await import('node:fs/promises');
  const yaml = await fs.readFile('.github/workflows/benchmark-release.yml', 'utf8');
  const comparison = yaml.split('\n  comparison:')[1].split('\n  trend-comparison:')[0];
  const promotion = yaml.split('\n  promotion-gate:')[1].split('\n  regression-approval:')[0];
  const approval = yaml.split('\n  regression-approval:')[1];
  assert.match(comparison, /release-module-comparison\.py/);
  assert.match(comparison, /release-module-evidence\.json/);
  assert.match(comparison, /ref: 08501bf55a373e27782c89cca348040fa1affa93/);
  assert.match(comparison, /gate: \$\{\{ steps\.module-comparison\.outputs\.gate \}\}/);
  assert.match(approval, /needs\.comparison\.outputs\.gate == 'approval_hold'/);
  assert.match(approval, /environment: benchmark-promotion/);
  assert.doesNotMatch(approval + promotion, /outputs\.status|regressed|workflow-support\.mjs gate|gate\.json/);
  assert.match(promotion, /needs\.comparison\.outputs\.gate/);
  assert.match(promotion, /needs\.regression-approval\.result/);
  assert.match(promotion, /blocked/);
  const diagnosticPublish = comparison.split('- name: Attach diagnostic trend')[1];
  assert.ok(diagnosticPublish, 'diagnostic trend publication must have its own failure boundary');
  assert.match(diagnosticPublish, /continue-on-error: true/);
});

test('promotion shell blocks unsuccessful prerequisites even with an approved regression', async () => {
  const fs = await import('node:fs/promises');
  const yaml = await fs.readFile('.github/workflows/benchmark-release.yml', 'utf8');
  const job = yaml.split('\n  promotion-gate:')[1].split('\n  regression-approval:')[0];
  const shell = job.split('        run: |\n')[1].split('\n').map((line) => line.replace(/^          /, '')).join('\n');
  for (const [build, comparison, gate, approval, expected] of [
    ['success', 'success', 'pass', 'skipped', 0],
    ['success', 'success', 'approval_hold', 'success', 0],
    ['success', 'success', 'approval_hold', 'failure', 1],
    ['success', 'success', 'blocked', 'success', 1],
    ['success', 'success', '', 'success', 1],
    ['failure', 'success', 'pass', 'success', 1],
    ['failure', 'success', 'approval_hold', 'success', 1],
    ['success', 'failure', 'approval_hold', 'success', 1],
    ['success', 'skipped', 'pass', 'success', 1],
  ]) {
    const result = spawnSync('bash', ['--noprofile', '--norc', '-eo', 'pipefail', '-c', shell], {
      encoding: 'utf8', env: { ...process.env, BUILD_RESULT: build, COMPARISON_RESULT: comparison,
        GATE: gate, APPROVAL_RESULT: approval },
    });
    assert.equal(result.status, expected, `${build}/${comparison}/${gate}/${approval}: ${result.stderr}`);
  }
});
