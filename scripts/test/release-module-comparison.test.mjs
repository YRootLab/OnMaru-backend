import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { readFile, mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';

const fixtures = resolve('scripts/test/fixtures/release-module-comparison');
const template = JSON.parse(await readFile(join(fixtures, 'record.json'), 'utf8'));
const toolkit = process.env.ONMARU_TOOLKIT_SRC ?? (existsSync('/tmp/onmaru-ci-toolkit-9c6f003/src')
  ? '/tmp/onmaru-ci-toolkit-9c6f003/src' : join(fixtures, 'upstream'));

test('offline comparison authority is byte-identical to the pinned upstream source', async () => {
  for (const [file, checksum] of [
    ['compare/module_benchmark.py', '19771fbe3fe7c0c737dd553bed62836a5f619e0e8af3e0e01a759929057d25ff'],
    ['contracts/module_evidence.py', 'cdc421ab2e02a1960d84e76bba8f823ed214481a6ab7a6d3b599d5444e26f161'],
  ]) {
    const source = await readFile(join(fixtures, 'upstream/pipeline_toolkit', file));
    assert.equal(createHash('sha256').update(source).digest('hex'), checksum, file);
  }
});

function evidence(values, candidate = false) {
  const commit = (candidate ? 'b' : 'a').repeat(40);
  return { schema_version: 1, release_tag: candidate ? 'v1.2.4' : 'v1.2.3', commit_sha: commit,
    records: values.map((value, index) => {
      const record = structuredClone(template);
      const id = String((candidate ? 201 : 101) + index);
      record.run_id = id;
      record.actions_url = `https://github.com/YRootLab/OnMaru-backend/actions/runs/${id}/attempts/1`;
      record.manifest_url = `https://github.com/YRootLab/OnMaru-backend/actions/runs/${id}/artifacts/901`;
      record.artifact_uri = record.manifest_url;
      record.provenance.commit_sha = commit;
      record.metric_value = value;
      return record;
    }) };
}

async function compare(baseline, candidate) {
  const directory = await mkdtemp(join(tmpdir(), 'release-module-comparison-'));
  try {
    if (baseline !== null) await writeFile(join(directory, 'baseline.json'), JSON.stringify(baseline));
    if (candidate !== null) await writeFile(join(directory, 'candidate.json'), typeof candidate === 'string' ? candidate : JSON.stringify(candidate));
    const result = spawnSync('python3', ['scripts/benchmark/release-module-comparison.py',
      '--baseline', join(directory, 'baseline.json'), '--candidate', join(directory, 'candidate.json'),
      '--baseline-tag', 'v1.2.3', '--baseline-sha', 'a'.repeat(40),
      '--candidate-tag', 'v1.2.4', '--candidate-sha', 'b'.repeat(40),
      '--repository', 'YRootLab/OnMaru-backend', '--output', join(directory, 'comparison.json'),
      '--github-output', join(directory, 'output.txt')],
    { encoding: 'utf8', env: { ...process.env, PYTHONPATH: toolkit } });
    assert.equal(result.status, 0, result.stderr);
    const report = JSON.parse(await readFile(join(directory, 'comparison.json'), 'utf8'));
    const output = await readFile(join(directory, 'output.txt'), 'utf8');
    assert.equal(output, `gate=${report.gate}\n`);
    return report;
  } finally { await rm(directory, { recursive: true, force: true }); }
}

test('two runs per side fail closed without requesting regression approval', async () => {
  const result = await compare(evidence([100, 100]), evidence([120, 120], true));
  assert.equal(result.classification, 'inconclusive');
  assert.equal(result.gate, 'blocked');
  assert.equal(result.policy_outcome, 'none');
});

test('three runs preserve values, medians, ranges and original evidence links', async () => {
  const result = await compare(evidence([90, 100, 110]), evidence([95, 105, 115], true));
  assert.equal(result.classification, 'comparable');
  assert.equal(result.baseline_median, 100);
  assert.equal(result.candidate_median, 105);
  assert.equal(result.relative_delta, 0.05);
  assert.deepEqual(result.sample_values, { baseline: [90, 100, 110], candidate: [95, 105, 115] });
  assert.deepEqual(result.sample_range, { baseline: 20, candidate: 20 });
  assert.equal(result.sources.candidate[0].actions_url, 'https://github.com/YRootLab/OnMaru-backend/actions/runs/201/attempts/1');
  assert.equal(result.sources.baseline[0].manifest_url, template.manifest_url);
  assert.equal(result.gate, 'pass');
});

for (const [value, gate] of [[115, 'pass'], [115.01, 'approval_hold']]) {
  test(`release median ${value}% maps to ${gate}`, async () => {
    const result = await compare(evidence([100, 100, 100]), evidence([value, value, value], true));
    assert.equal(result.gate, gate);
    assert.equal(result.policy_outcome, gate === 'pass' ? 'none' : 'approval_hold');
  });
}

for (const [value, gate] of [[1.61, 'pass'], [1.6100000000000003, 'approval_hold']]) {
  test(`decimal baseline 1.4 and candidate ${value} maps to ${gate}`, async () => {
    const result = await compare(evidence([1.4, 1.4, 1.4]), evidence([value, value, value], true));
    assert.equal(result.classification, 'comparable');
    assert.equal(result.gate, gate);
    assert.equal(result.policy_outcome, gate === 'pass' ? 'none' : 'approval_hold');
    if (gate === 'pass') assert.equal(result.relative_delta, 0.15);
  });
}

for (const token of ['true', 'false', 'NaN', 'Infinity', '-Infinity']) {
  test(`invalid numeric token ${token} fails closed without approval`, async () => {
    const raw = JSON.stringify(evidence([120, 120, 120], true)).replace('"metric_value":120', `"metric_value":${token}`);
    const result = await compare(evidence([100, 100, 100]), raw);
    assert.equal(result.classification, 'inconclusive');
    assert.equal(result.gate, 'blocked');
    assert.equal(result.policy_outcome, 'none');
    assert.equal(result.valid_sample_count.candidate, 2);
  });
}

for (const mutation of ['duplicate', 'failed', 'cancelled', 'missing', 'mismatch', 'unsafe', 'identity', 'nonfinite', 'extra']) {
  test(`${mutation} evidence cannot authorize promotion`, async () => {
    const candidate = evidence([120, 120, 120], true);
    const record = candidate.records[2];
    if (mutation === 'duplicate') candidate.records[2] = structuredClone(candidate.records[1]);
    if (mutation === 'failed') record.status = 'failed';
    if (mutation === 'cancelled') record.status = 'cancelled';
    if (mutation === 'missing') record.artifact_uri = null;
    if (mutation === 'mismatch') record.environment_identity.cache_state = 'warm';
    if (mutation === 'unsafe') record.manifest_url = 'https://user:secret@github.com/evil?token=private';
    if (mutation === 'identity') record.provenance.commit_sha = 'c'.repeat(40);
    if (mutation === 'nonfinite') record.metric_value = -1;
    if (mutation === 'extra') candidate.records.push(structuredClone(record));
    const result = await compare(evidence([100, 100, 100]), candidate);
    assert.equal(result.classification, 'inconclusive');
    assert.equal(result.gate, 'blocked');
    assert.equal(result.policy_outcome, 'none');
    assert.ok(result.exclusions.length > 0 || ['comparability_key_mismatch', 'duplicate_run_id'].includes(result.reason));
    assert.doesNotMatch(JSON.stringify(result), /secret|private|user:/);
  });
}

test('all failed evidence preserves failed classification without regression approval', async () => {
  const candidate = evidence([120, 120, 120], true);
  candidate.records.forEach((record) => { record.status = 'failed'; });
  const result = await compare(evidence([100, 100, 100]), candidate);
  assert.equal(result.classification, 'failed');
  assert.equal(result.gate, 'blocked');
  assert.equal(result.policy_outcome, 'none');
});

test('zero baseline and absent files fail closed', async () => {
  for (const baseline of [evidence([0, 0, 0]), null]) {
    const result = await compare(baseline, evidence([120, 120, 120], true));
    assert.equal(result.classification, 'inconclusive');
    assert.equal(result.gate, 'blocked');
    assert.equal(result.policy_outcome, 'none');
  }
});

test('unallowlisted logs and secrets are not reflected into canonical evidence', async () => {
  const candidate = evidence([100, 100, 100], true);
  candidate.records[0].logs = 'secret-credential';
  candidate.authorization = 'secret-credential';
  const result = await compare(evidence([100, 100, 100]), candidate);
  assert.equal(result.gate, 'pass');
  assert.doesNotMatch(JSON.stringify(result), /secret-credential|authorization|logs/);
});
