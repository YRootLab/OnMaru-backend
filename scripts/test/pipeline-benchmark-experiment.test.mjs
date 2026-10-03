import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, mkdirSync, chmodSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const file = (path) => readFileSync(path, 'utf8');
const mod = () => import('../benchmark/pipeline-experiment.mjs');
const a = 'a'.repeat(40), b = 'b'.repeat(40), tree = 'c'.repeat(40);
const repo = 'YRootLab/OnMaru-backend';
const context = { repository: repo, baseline_ref: a, candidate_ref: b, scope: 'ci', reason: 'cache 비교',
  experiment_run_id: 900, experiment_run_attempt: 1, controller_sha: a, controller_ref: 'refs/heads/develop' };
const planRaw = Buffer.from(JSON.stringify({ version: 1, scopes: {
  ci: { suite: 'full-java-build', commands: [{ argv: ['./gradlew', ':apps:spring-api:test', ':apps:spring-api:bootJar'], config: 'gradle' }], modules: ['apps:spring-api'], filters: [], fixture: 'repository-default', seed: 'repository-default', dependency_mode: 'gradle-wrapper-locks' },
  test: { suite: 'full-java-test', commands: [{ argv: ['./gradlew', ':apps:spring-api:test'], config: 'gradle' }], modules: ['apps:spring-api'], filters: [], fixture: 'repository-default', seed: 'repository-default', dependency_mode: 'gradle-wrapper-locks' },
} }) + '\n');

function fake({ conclusion = 'success', missingTime = false, duplicate = false, mismatch = false,
  moved = false, lostReceipt = false, modifiedWorker = false, missingArtifact = false, forgedEnvironment = false,
  planBytes = planRaw, wrongRunUrl = false } = {}) {
  const requests = [], runs = new Map();
  let n = 100;
  const environment = { runner_image: 'ubuntu24/20261001', java_version: '21', python_version: '3.12',
    cache_state: 'cold', database_fixture: 'repository-default', cpu_memory_profile: '4cpu/16GiB',
    dependency_mode: 'gradle-wrapper-locks', config_catalog_hash: createHash('sha256').update('gradle/max-workers:integer:1..4;build-cache:boolean;cache:cold;runner:ubuntu-24.04').digest('hex') };
  if (forgedEnvironment) environment.cache_state = 'warm';
  const source_identity = { application_source_commit: a, application_source_tree: tree,
    test_plan_sha256: createHash('sha256').update(planBytes).digest('hex') };
  return { requests, async request(path, options = {}) {
    requests.push({ path, ...options });
    if (path === '') return { full_name: repo, fork: false };
    if (path.startsWith('/git/ref/heads/')) return { object: { sha: path.endsWith('develop') ? a : moved && n > 100 ? a : b } };
    if (path === '/branches?per_page=100&page=1') return [{ name: 'develop', commit: { sha: a } }, { name: 'feature/556-cache', commit: { sha: b } }];
    if (path === `/git/commits/${a}`) return { sha: a, tree: { sha: tree } };
    if (path.startsWith('/contents/')) {
      const bytes = path.includes('test-plan') ? planBytes : path.includes('gradle.properties') ? Buffer.from('onmaru.ci.performance.max-workers=4\nonmaru.ci.performance.build-cache=true\n') : Buffer.from(modifiedWorker && path.endsWith(b) ? 'malicious worker' : 'trusted worker');
      return { type: 'file', encoding: 'base64', content: bytes.toString('base64'), sha: createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex') };
    }
    if (path.endsWith('/dispatches')) {
      const id = duplicate ? 101 : ++n, inputs = options.body.inputs;
      runs.set(id, { id, run_attempt: 1, head_sha: inputs.side === 'baseline' ? a : b, path: '.github/workflows/pipeline-benchmark-sample.yml', event: 'workflow_dispatch',
        html_url: wrongRunUrl ? 'https://example.invalid/run' : `https://github.com/${repo}/actions/runs/${id}`,
        head_repository: { full_name: repo }, repository: { full_name: repo },
        display_title: `pipeline-experiment/900/${inputs.side}/${inputs.ordinal}`, status: 'completed', conclusion,
        inputs });
      return lostReceipt ? {} : { workflow_run_id: id, html_url: `https://github.com/${repo}/actions/runs/${id}` };
    }
    const match = path.match(/^\/actions\/runs\/(\d+)/);
    if (match) {
      const run = runs.get(Number(match[1]));
      if (path.includes('/timing')) return missingTime ? {} : { run_duration_ms: 120000 };
      if (path.includes('/artifacts')) return { total_count: missingArtifact ? 0 : 1, artifacts: missingArtifact ? [] : [{ id: run.id + 1000, name: 'pipeline-experiment-sample-1', expired: false, size_in_bytes: 500, workflow_run: { id: run.id, head_sha: run.head_sha } }] };
      return run;
    }
    throw new Error(`Unexpected API ${path}`);
  }, async sample(artifact) {
    const run = runs.get(artifact.workflow_run.id);
    return { version: 1, experiment_run_id: 900, side: run.inputs.side, ordinal: Number(run.inputs.ordinal), run_id: run.id, run_attempt: 1,
      commit_sha: run.head_sha, source_identity: { ...source_identity, ...(mismatch ? { application_source_tree: b } : {}) }, scope: run.inputs.scope, suite: run.inputs.scope === 'ci' ? 'full-java-build' : 'full-java-test', environment_identity: environment,
      status: 'success', config: { max_workers: 4, build_cache: true } };
  } };
}

test('manual workflows expose four controller inputs and isolate signer permissions', () => {
  const parse = (path) => {
    const out = spawnSync('ruby', ['-r', 'yaml', '-r', 'json', '-e', 'puts JSON.generate(YAML.load_file(ARGV[0]))', path], { encoding: 'utf8' });
    assert.equal(out.status, 0, out.stderr); return JSON.parse(out.stdout);
  };
  const controller = parse('.github/workflows/pipeline-benchmark-experiment.yml');
  const worker = parse('.github/workflows/pipeline-benchmark-sample.yml');
  assert.deepEqual(Object.keys(controller.on ?? controller.true), ['workflow_dispatch']);
  assert.deepEqual(Object.keys((controller.on ?? controller.true).workflow_dispatch.inputs).sort(), ['baseline_ref', 'candidate_ref', 'reason', 'scope']);
  assert.deepEqual(Object.keys(worker.on ?? worker.true), ['workflow_dispatch']);
  assert.equal(controller.concurrency['cancel-in-progress'], false);
  assert.equal(worker.concurrency['cancel-in-progress'], false);
  assert.equal(worker['run-name'], 'pipeline-experiment/${{ inputs.experiment_run_id }}/${{ inputs.side }}/${{ inputs.ordinal }}');
  assert.equal(worker.jobs.sample.permissions['id-token'], undefined);
  assert.equal(worker.jobs.sample.permissions.attestations, undefined);
  assert.equal(worker.jobs.sample.permissions.actions, undefined);
  assert.equal(controller.jobs.attest.permissions['id-token'], 'write');
  assert.equal(controller.jobs.attest.permissions.attestations, 'write');
  assert.equal(controller.jobs.control.permissions['id-token'], undefined);
  assert.equal(worker.jobs.sample.strategy, undefined);
  assert.doesNotMatch(JSON.stringify(worker), /secrets\.|OTLP|environment"/);
  const steps = controller.jobs.attest.steps;
  const attest = steps.find(s => s.uses?.startsWith('actions/attest-build-provenance@'));
  const upload = steps.find(s => s.with?.name === 'pipeline-experiment-manifest-${{ github.run_attempt }}');
  assert.equal(attest.with['subject-path'], upload.with.path);
  const attestIndex = steps.indexOf(attest), uploadIndex = steps.indexOf(upload);
  assert.ok(attestIndex < uploadIndex);
  assert.equal(steps.slice(attestIndex + 1, uploadIndex).some(s => s.run), false);
});

test('six distinct dispatched runs produce a fixed-source manifest with raw committed-plan digest', async () => {
  const { executeExperiment } = await mod(), api = fake();
  const result = await executeExperiment(api, context, { sleep: async () => {} });
  assert.equal(result.diagnostic.status, 'complete');
  assert.equal(result.manifest.policy_version, 'pipeline-experiment/2');
  assert.deepEqual(result.manifest.observations.map(x => [x.side, x.ordinal]), [['baseline', 1], ['candidate', 1], ['baseline', 2], ['candidate', 2], ['baseline', 3], ['candidate', 3]]);
  assert.equal(new Set(result.manifest.observations.map(x => x.run_id)).size, 6);
  assert.ok(result.manifest.observations.every(x => x.value === 120 && x.source_identity.application_source_commit === a && x.source_identity.application_source_tree === tree));
  const dispatches = api.requests.filter(x => x.method === 'POST');
  assert.equal(dispatches.length, 6);
  assert.ok(dispatches.every(x => ['develop', 'feature/556-cache'].includes(x.body.ref) && x.body.inputs.application_source_commit === a));
});

for (const [name, options, reason] of [
  ['failure', { conclusion: 'failure' }, 'sample_failure'], ['cancelled', { conclusion: 'cancelled' }, 'sample_cancelled'],
  ['missing wall clock', { missingTime: true }, 'wall_clock_unavailable'], ['duplicate runs', { duplicate: true }, 'duplicate_run'],
  ['different source tree', { mismatch: true }, 'sample_source_mismatch'], ['ref moved', { moved: true }, 'refs_moved'],
  ['lost dispatch receipt', { lostReceipt: true }, 'dispatch_receipt_lost'], ['changed worker', { modifiedWorker: true }, 'untrusted_sample_workflow'],
  ['missing artifact', { missingArtifact: true }, 'sample_artifact_unavailable'],
  ['forged environment policy', { forgedEnvironment: true }, 'sample_environment_mismatch'],
  ['wrong run URL', { wrongRunUrl: true }, 'sample_run_mismatch'],
]) test(`${name} leaves inconclusive diagnostics and never fabricates a manifest`, async () => {
  const { executeExperiment } = await mod(), api = fake(options);
  const result = await executeExperiment(api, context, { sleep: async () => {} });
  assert.equal(result.manifest, null);
  assert.equal(result.diagnostic.status, 'inconclusive');
  assert.ok(result.diagnostic.errors.includes(reason), JSON.stringify(result));
  assert.ok(api.requests.filter(x => x.method === 'POST').length <= 6);
  if (options.lostReceipt) assert.equal(api.requests.filter(x => x.method === 'POST').length, 1);
  if (options.modifiedWorker) assert.equal(api.requests.filter(x => x.method === 'POST').length, 0);
});

test('scope selection uses committed argv and rejects command-bearing candidate configuration', async () => {
  const { selectPlan, parseConfig, commandArguments } = await mod();
  const plan = selectPlan(planRaw, 'test');
  assert.equal(plan.suite, 'full-java-test');
  assert.deepEqual(commandArguments(plan.commands[0], { max_workers: 2, build_cache: false }), ['./gradlew', ':apps:spring-api:test', '-Ponmaru.ci.performance.enabled=true', '-Ponmaru.ci.performance.max-workers=2', '-Ponmaru.ci.performance.build-cache=false']);
  assert.throws(() => parseConfig('onmaru.ci.performance.max-workers=$(curl bad)\nonmaru.ci.performance.build-cache=true'), /invalid_config/);
  assert.throws(() => selectPlan(planRaw, 'cd'), /unsupported_scope/);
  assert.throws(() => parseConfig('onmaru.ci.performance.max-workers=8\nonmaru.ci.performance.build-cache=true'), /invalid_config/);
  const mixed = JSON.parse(planRaw); mixed.scope = 'ci';
  assert.throws(() => selectPlan(Buffer.from(JSON.stringify(mixed)), 'ci'), /invalid_plan/);
  const unknown = JSON.parse(planRaw); unknown.scopes.cd = unknown.scopes.ci;
  assert.throws(() => selectPlan(Buffer.from(JSON.stringify(unknown)), 'ci'), /invalid_plan/);
});

test('both committed scopes run the same complete Java module suite without test filters', async () => {
  const { selectPlan } = await mod();
  const raw = readFileSync('.github/pipeline-benchmark-test-plan.json');
  for (const scope of ['ci', 'test']) {
    const plan = selectPlan(raw, scope);
    assert.deepEqual(plan.filters, []);
    for (const task of [':adapters:tourism-api:test', ':modules:insights:test', ':modules:catalog:test', ':modules:audio:test', ':modules:community:test', ':modules:identity:test', ':modules:journey:test', ':modules:operations:test', ':apps:spring-api:test']) {
      assert.ok(plan.commands[0].argv.includes(task), `${scope}: ${task}`);
    }
  }
});

test('test scope independently yields six verified full-java-test observations', async () => {
  const { executeExperiment } = await mod();
  const result = await executeExperiment(fake(), { ...context, scope: 'test' });
  assert.equal(result.diagnostic.status, 'complete');
  assert.equal(result.manifest.scope, 'test');
  assert.ok(result.manifest.observations.every(item => item.suite === 'full-java-test'));
});

test('worker verifies the actual Git tree and executes only committed argv with a scrubbed environment', async () => {
  const { verifyCheckout, runCommittedPlan } = await mod();
  const directory = mkdtempSync(join(tmpdir(), 'pipeline-source-'));
  const source = join(directory, 'source'), output = join(directory, 'argv.json');
  try {
    mkdirSync(join(source, '.github'), { recursive: true });
    writeFileSync(join(source, '.github/pipeline-benchmark-test-plan.json'), planRaw);
    writeFileSync(join(source, 'gradlew'), `#!/usr/bin/env node\nrequire('fs').writeFileSync(${JSON.stringify(output)}, JSON.stringify({argv:process.argv.slice(2),keys:Object.keys(process.env)}));\n`);
    chmodSync(join(source, 'gradlew'), 0o755);
    const git = (...args) => { const r = spawnSync('git', args, { cwd: source, encoding: 'utf8' }); assert.equal(r.status, 0, r.stderr); return r.stdout.trim(); };
    git('init', '-q'); git('add', '.'); git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture');
    const sha = git('rev-parse', 'HEAD'), checked = verifyCheckout(source, sha, 'ci');
    assert.equal(checked.source_identity.application_source_tree, git('rev-parse', 'HEAD^{tree}'));
    assert.equal(checked.source_identity.test_plan_sha256, createHash('sha256').update(planRaw).digest('hex'));
    const prior = process.env.ACTIONS_ID_TOKEN_REQUEST_TOKEN;
    process.env.ACTIONS_ID_TOKEN_REQUEST_TOKEN = 'fixture-sensitive-token';
    try { runCommittedPlan(source, checked.plan, { max_workers: 2, build_cache: false }); }
    finally { if (prior === undefined) delete process.env.ACTIONS_ID_TOKEN_REQUEST_TOKEN; else process.env.ACTIONS_ID_TOKEN_REQUEST_TOKEN = prior; }
    const executed = JSON.parse(file(output));
    assert.deepEqual(executed.argv, [':apps:spring-api:test', ':apps:spring-api:bootJar', '-Ponmaru.ci.performance.enabled=true', '-Ponmaru.ci.performance.max-workers=2', '-Ponmaru.ci.performance.build-cache=false']);
    assert.equal(executed.keys.some(key => /TOKEN|OTLP|ACTIONS_|GITHUB_/.test(key)), false);
    verifyCheckout(source, sha, 'ci');
    writeFileSync(join(source, 'untracked'), 'changed');
    assert.throws(() => verifyCheckout(source, sha, 'ci'), /dirty_application_source/);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('sample archive decoding accepts one JSON document and rejects additional or oversized members', async () => {
  const { decodeSampleArchive } = await mod();
  const zip = (entries) => {
    const result = spawnSync('python3', ['-c', 'import sys,io,json,zipfile\nb=io.BytesIO()\nwith zipfile.ZipFile(b,"w",zipfile.ZIP_DEFLATED) as z:\n for name,data in json.load(sys.stdin): z.writestr(name,data)\nsys.stdout.buffer.write(b.getvalue())'], { input: JSON.stringify(entries), maxBuffer: 2 * 1024 * 1024 });
    assert.equal(result.status, 0); return result.stdout;
  };
  assert.deepEqual(decodeSampleArchive(zip([['sample.json', '{"version":1}']])), { version: 1 });
  assert.throws(() => decodeSampleArchive(zip([['sample.json', '{}'], ['extra.json', '{}']])), /unsafe_sample_archive/);
  assert.throws(() => decodeSampleArchive(zip([['sample.json', ' '.repeat(1024 * 1024 + 1)]])), /unsafe_sample_archive/);
});

const toolkitSource = process.env.ONMARU_TOOLKIT_SRC ?? '/tmp/onmaru-ci-toolkit-9c6f003/src';

test('root README explains the observable benchmark flow and installation boundary', () => {
  const rootReadme = file('README.md');
  for (const phrase of [
    'Collector → Prometheus·Tempo → Grafana',
    'Grafana, Prometheus, Tempo, OpenTelemetry Collector를 따로 설치할 필요는 없다',
    'docker compose -f observability/local/compose.yaml up',
    'http://127.0.0.1:3000/d/toolkit-ci-benchmark',
    'run_experiment.py dispatch',
    '--authorize-dispatch',
    'Toolkit의 Markdown/JSON 결과가 정본',
    '(baseline 중앙값 - candidate 중앙값) / baseline 중앙값 × 100',
  ]) assert.match(rootReadme, new RegExp(phrase.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  assert.match(rootReadme, /baseline 3회.*candidate 3회/s);
  assert.match(rootReadme, /Docker.*Spring.*FastAPI.*컨테이너/s);
  assert.match(rootReadme, /dry-run.*기본/s);
});

test('pinned Toolkit authenticates both committed scopes and compares the producer manifest (offline API fixture)', { skip: !existsSync(toolkitSource) }, async () => {
  const { executeExperiment } = await mod();
  const raw = readFileSync('.github/pipeline-benchmark-test-plan.json');
  for (const scope of ['ci', 'test']) {
    const api = fake({ planBytes: raw });
    const produced = await executeExperiment(api, { ...context, scope });
    assert.equal(produced.diagnostic.status, 'complete');
    const runs = {};
    for (const observation of produced.manifest.observations) runs[`${observation.run_id}:1`] = await api.request(`/actions/runs/${observation.run_id}/attempts/1`);
    const source = `import json,sys,base64,hashlib
from pipeline_toolkit.experiments.collect import _verify_source
from pipeline_toolkit.experiments.compare import compare_collection
from pipeline_toolkit.experiments.contract import select_test_plan_scope
payload=json.load(sys.stdin); manifest=payload['manifest']; raw=base64.b64decode(payload['raw'])
assert select_test_plan_scope(json.loads(raw), manifest['scope'])['suite']==manifest['observations'][0]['suite']
class GitHubFixture:
 def api(self,path):
  identity=manifest['observations'][0]['source_identity']
  if '/git/commits/' in path: return {'sha':identity['application_source_commit'],'tree':{'sha':identity['application_source_tree']}}
  assert '/contents/.github/pipeline-benchmark-test-plan.json?ref=' in path
  return {'type':'file','encoding':'base64','content':payload['raw'],'sha':hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\\0'+raw).hexdigest()}
checks={str(x['run_id'])+':1':_verify_source(GitHubFixture(),x,manifest) for x in manifest['observations']}
result=compare_collection({'manifest':manifest,'runs':payload['runs'],'artifact_checks':{key:'verified' for key in checks},'identity_checks':checks,'manifest_attestation':'verified'})
assert result['verdict']=='no_regression', result
print(json.dumps({'scope':manifest['scope'],'verified_sources':len(checks),'verdict':result['verdict']}))
`;
    const checked = spawnSync('python3', ['-c', source], { input: JSON.stringify({ manifest: produced.manifest, runs, raw: raw.toString('base64') }), encoding: 'utf8', env: { ...process.env, PYTHONPATH: toolkitSource } });
    assert.equal(checked.status, 0, checked.stderr);
    assert.deepEqual(JSON.parse(checked.stdout), { scope, verified_sources: 6, verdict: 'no_regression' });
  }
});
