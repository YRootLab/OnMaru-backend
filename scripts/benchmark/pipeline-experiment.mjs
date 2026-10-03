import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync, appendFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { cpus, totalmem } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPOSITORY = 'YRootLab/OnMaru-backend';
const WORKER = '.github/workflows/pipeline-benchmark-sample.yml';
const PLAN = '.github/pipeline-benchmark-test-plan.json';
const LIMIT = 1024 * 1024;
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
const need = (condition, code) => { if (!condition) throw new Error(code); };
const isSha = (value) => typeof value === 'string' && /^[a-f0-9]{40}$/.test(value);
const positive = (value) => Number.isSafeInteger(value) && value > 0;
const runUrl = (id) => `https://github.com/${REPOSITORY}/actions/runs/${id}`;
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const environmentKeys = ['runner_image', 'java_version', 'python_version', 'cache_state', 'database_fixture', 'cpu_memory_profile', 'dependency_mode', 'config_catalog_hash'];
const configCatalog = 'gradle/max-workers:integer:1..4;build-cache:boolean;cache:cold;runner:ubuntu-24.04';

export function selectPlan(raw, scope) {
  need(['ci', 'test'].includes(scope), 'unsupported_scope');
  need(raw.length <= LIMIT, 'invalid_plan');
  const document = JSON.parse(raw);
  need(document && document.version === 1 && !('scope' in document) && !('suite' in document) &&
    document.scopes && typeof document.scopes === 'object' && !Array.isArray(document.scopes) &&
    Object.keys(document.scopes).every(key => ['ci', 'test'].includes(key)) &&
    Object.values(document.scopes).every(entry => entry && typeof entry.suite === 'string' && entry.suite.trim()), 'invalid_plan');
  const plan = document.scopes?.[scope];
  need(document.version === 1 && plan && typeof plan.suite === 'string' && /^[a-z0-9-]{1,64}$/.test(plan.suite), 'invalid_plan');
  need(Array.isArray(plan.commands) && plan.commands.length > 0 && plan.commands.length <= 10, 'invalid_plan');
  for (const command of plan.commands) {
    need(command.config === 'gradle' && Array.isArray(command.argv) && command.argv[0] === './gradlew' &&
      command.argv.every(arg => typeof arg === 'string' && arg.length > 0 && arg.length < 256 && !/[\x00-\x1f]/.test(arg)), 'invalid_plan');
  }
  need(Array.isArray(plan.modules) && plan.modules.length > 0 && Array.isArray(plan.filters) &&
    ['fixture', 'seed', 'dependency_mode'].every(key => typeof plan[key] === 'string' && plan[key].length > 0), 'invalid_plan');
  return plan;
}

// Only these two typed values cross from the candidate configuration into argv.
// No candidate scripts, task selectors, workflow steps or arbitrary flags execute.
export function parseConfig(raw) {
  const lines = String(raw).split(/\r?\n/);
  const value = (key) => {
    const found = lines.filter(line => line.startsWith(`${key}=`));
    need(found.length === 1, 'invalid_config'); return found[0].slice(key.length + 1);
  };
  const workers = value('onmaru.ci.performance.max-workers');
  const cache = value('onmaru.ci.performance.build-cache');
  need(/^[1-4]$/.test(workers) && /^(true|false)$/.test(cache), 'invalid_config');
  return { max_workers: Number(workers), build_cache: cache === 'true' };
}

export function commandArguments(command, config) {
  need(Number.isInteger(config.max_workers) && config.max_workers >= 1 && config.max_workers <= 4 && typeof config.build_cache === 'boolean', 'invalid_config');
  return [...command.argv, '-Ponmaru.ci.performance.enabled=true', `-Ponmaru.ci.performance.max-workers=${config.max_workers}`, `-Ponmaru.ci.performance.build-cache=${config.build_cache}`];
}

function validateContext(c) {
  need(c.repository === REPOSITORY && c.controller_ref === 'refs/heads/develop' && c.controller_sha === c.baseline_ref, 'untrusted_controller');
  need(isSha(c.baseline_ref) && isSha(c.candidate_ref) && c.baseline_ref !== c.candidate_ref, 'invalid_refs');
  need(positive(c.experiment_run_id) && c.experiment_run_attempt === 1, 'invalid_experiment_identity');
  need(['ci', 'test'].includes(c.scope), 'unsupported_scope');
  need(typeof c.reason === 'string' && c.reason.trim().length > 0 && c.reason.length <= 512 && !/[\x00-\x1f\x7f]/.test(c.reason), 'invalid_reason');
}

async function content(api, path, sha) {
  const doc = await api.request(`/contents/${path}?ref=${sha}`);
  need(doc.type === 'file' && doc.encoding === 'base64' && typeof doc.content === 'string' && doc.content.length < LIMIT * 2, 'invalid_committed_file');
  const encoded = doc.content.replace(/\n/g, '');
  need(/^[A-Za-z0-9+/]*={0,2}$/.test(encoded), 'invalid_committed_file');
  const bytes = Buffer.from(encoded, 'base64');
  need(bytes.length <= LIMIT && createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex') === doc.sha, 'committed_blob_mismatch');
  return bytes;
}

async function prepare(api, c) {
  validateContext(c);
  const repository = await api.request('');
  need(repository.full_name === REPOSITORY && repository.fork === false, 'wrong_repository');
  const commit = await api.request(`/git/commits/${c.baseline_ref}`);
  need(commit.sha === c.baseline_ref && isSha(commit.tree?.sha), 'invalid_application_source');
  const raw = await content(api, PLAN, c.baseline_ref);
  const plan = selectPlan(raw, c.scope);
  const trustedWorker = await content(api, WORKER, c.baseline_ref);
  need(trustedWorker.equals(await content(api, WORKER, c.candidate_ref)), 'untrusted_sample_workflow');
  const configs = {};
  for (const side of ['baseline', 'candidate']) configs[side] = parseConfig(await content(api, 'gradle.properties', c[`${side}_ref`]));
  return { plan, configs, source_identity: { application_source_commit: c.baseline_ref,
    application_source_tree: commit.tree.sha, test_plan_sha256: sha256(raw) } };
}

async function resolveBranches(api, c) {
  const candidates = [];
  for (let page = 1; page <= 10; page++) {
    const branches = await api.request(`/branches?per_page=100&page=${page}`);
    need(Array.isArray(branches), 'invalid_branches');
    candidates.push(...branches.filter(x => x.name?.startsWith('feature/') && x.commit?.sha === c.candidate_ref));
    if (branches.length < 100) break;
    need(page < 10, 'branch_limit');
  }
  need(candidates.length === 1, 'candidate_branch_ambiguous');
  return { baseline: 'develop', candidate: candidates[0].name };
}

async function assertRefs(api, c, branches) {
  for (const side of ['baseline', 'candidate']) {
    const ref = await api.request(`/git/ref/heads/${encodeURIComponent(branches[side])}`);
    need(ref.object?.sha === c[`${side}_ref`], 'refs_moved');
  }
}

function sampleIdentity(run, c, receipt) {
  need(run.id === receipt.run_id && run.run_attempt === 1 && run.head_sha === c[`${receipt.side}_ref`] &&
    run.html_url === runUrl(receipt.run_id) &&
    run.event === 'workflow_dispatch' && run.path === WORKER && run.repository?.full_name === REPOSITORY &&
    run.head_repository?.full_name === REPOSITORY && run.display_title === `pipeline-experiment/${c.experiment_run_id}/${receipt.side}/${receipt.ordinal}`, 'sample_run_mismatch');
}

async function collectOne(api, c, prepared, receipt, options) {
  let run;
  for (let poll = 0; poll < 1000; poll++) {
    need(Date.now() < options.deadline, 'sample_timeout');
    run = await api.request(`/actions/runs/${receipt.run_id}/attempts/1`);
    sampleIdentity(run, c, receipt);
    if (run.status === 'completed') break;
    await options.sleep(5000);
  }
  need(run.status === 'completed', 'sample_timeout');
  need(run.conclusion === 'success', `sample_${['failure', 'cancelled', 'timed_out', 'skipped', 'action_required', 'startup_failure', 'stale', 'neutral'].includes(run.conclusion) ? run.conclusion : 'unknown'}`);
  const listing = await api.request(`/actions/runs/${run.id}/artifacts?per_page=100`);
  need(Array.isArray(listing.artifacts) && listing.total_count === listing.artifacts.length && listing.total_count <= 100, 'partial_artifacts');
  const matches = listing.artifacts.filter(x => x.name === 'pipeline-experiment-sample-1');
  need(matches.length === 1, 'sample_artifact_unavailable');
  const artifact = matches[0];
  need(positive(artifact.id) && artifact.expired === false && Number.isInteger(artifact.size_in_bytes) && artifact.size_in_bytes > 0 && artifact.size_in_bytes <= LIMIT && artifact.workflow_run?.id === run.id && artifact.workflow_run?.head_sha === run.head_sha, 'sample_artifact_unavailable');
  const sample = await api.sample(artifact);
  need(sample.version === 1 && sample.status === 'success' && sample.experiment_run_id === c.experiment_run_id &&
    sample.run_id === run.id && sample.run_attempt === 1 && sample.side === receipt.side && sample.ordinal === receipt.ordinal &&
    sample.commit_sha === run.head_sha && sample.scope === c.scope && sample.suite === prepared.plan.suite, 'sample_evidence_mismatch');
  const source = prepared.source_identity;
  need(Object.keys(source).every(key => sample.source_identity?.[key] === source[key]), 'sample_source_mismatch');
  need(same(sample.config, prepared.configs[receipt.side]), 'sample_config_mismatch');
  need(environmentKeys.every(key => typeof sample.environment_identity?.[key] === 'string' && sample.environment_identity[key].length > 0 && sample.environment_identity[key].length <= 256), 'sample_environment_missing');
  need(sample.environment_identity.cache_state === 'cold' && sample.environment_identity.database_fixture === prepared.plan.fixture &&
    sample.environment_identity.dependency_mode === prepared.plan.dependency_mode && sample.environment_identity.config_catalog_hash === sha256(configCatalog), 'sample_environment_mismatch');
  let timing;
  try { timing = await api.request(`/actions/runs/${run.id}/timing`); }
  catch { throw new Error('wall_clock_unavailable'); }
  // The run usage API's whole-run duration is the sole timing source. updated_at,
  // longest job/module or a min/max job envelope are not workflow wall clock.
  need(Number.isFinite(timing.run_duration_ms) && timing.run_duration_ms >= 0, 'wall_clock_unavailable');
  const current = await api.request(`/actions/runs/${run.id}`);
  sampleIdentity(current, c, receipt);
  need(current.status === 'completed' && current.conclusion === 'success', 'sample_changed');
  return { side: receipt.side, ordinal: receipt.ordinal, run_id: run.id, run_attempt: 1, commit_sha: run.head_sha,
    source_identity: { ...source }, value: timing.run_duration_ms / 1000, suite: prepared.plan.suite,
    environment_identity: Object.fromEntries(environmentKeys.map(key => [key, sample.environment_identity[key]])),
    manifest_url: `${runUrl(run.id)}/artifacts/${artifact.id}` };
}

export async function collectExperiment(api, c, receipts, options = {}) {
  const diagnostic = { status: 'inconclusive', errors: [], samples: [] };
  const observations = [];
  try {
    const prepared = await prepare(api, c);
    need(Array.isArray(receipts) && receipts.length === 6 && new Set(receipts.map(x => x.run_id)).size === 6, 'duplicate_run');
    const expected = new Set(['baseline/1', 'baseline/2', 'baseline/3', 'candidate/1', 'candidate/2', 'candidate/3']);
    for (const receipt of receipts) {
      need(positive(receipt.run_id) && expected.delete(`${receipt.side}/${receipt.ordinal}`), 'invalid_sample_receipts');
      try {
        const observation = await collectOne(api, c, prepared, receipt, {
          deadline: options.deadline ?? Date.now() + 3300_000,
          sleep: options.sleep ?? (ms => new Promise(done => setTimeout(done, ms))),
        });
        observations.push(observation);
        diagnostic.samples.push({ ...receipt, status: 'success' });
      } catch (error) {
        diagnostic.errors.push(safeError(error)); diagnostic.samples.push({ ...receipt, status: 'excluded', reason: safeError(error) });
      }
    }
    need(diagnostic.errors.length === 0, 'incomplete_samples');
    need(observations.every(x => same(x.environment_identity, observations[0].environment_identity)), 'environment_mismatch');
    diagnostic.status = 'complete';
    return { diagnostic, manifest: { version: 1, policy_version: 'pipeline-experiment/2', repository: REPOSITORY,
      baseline_ref: c.baseline_ref, candidate_ref: c.candidate_ref, scope: c.scope,
      experiment_run_id: c.experiment_run_id, experiment_run_attempt: c.experiment_run_attempt, observations } };
  } catch (error) { diagnostic.errors.push(safeError(error)); return { diagnostic, manifest: null }; }
}

const safeError = (error) => /^[a-z_]{1,80}$/.test(error.message) ? error.message : 'operation_failed';

export async function executeExperiment(api, c, options = {}) {
  const receipts = [];
  try {
    await prepare(api, c);
    const branches = await resolveBranches(api, c);
    for (let ordinal = 1; ordinal <= 3; ordinal++) for (const side of ['baseline', 'candidate']) {
      await assertRefs(api, c, branches);
      // A dispatch is never retried, including when its receipt is lost.
      const response = await api.request('/actions/workflows/pipeline-benchmark-sample.yml/dispatches', { method: 'POST', body: {
        ref: branches[side], inputs: { experiment_run_id: String(c.experiment_run_id), side, ordinal: String(ordinal),
          application_source_commit: c.baseline_ref, config_commit: c[`${side}_ref`], scope: c.scope },
      } });
      need(positive(response.workflow_run_id) && response.html_url === runUrl(response.workflow_run_id), 'dispatch_receipt_lost');
      need(!receipts.some(x => x.run_id === response.workflow_run_id), 'duplicate_run');
      receipts.push({ side, ordinal, run_id: response.workflow_run_id });
      options.onReceipt?.(receipts);
      await assertRefs(api, c, branches);
    }
    return { ...await collectExperiment(api, c, receipts, options), receipts };
  } catch (error) {
    return { manifest: null, receipts, diagnostic: { status: 'inconclusive', errors: [safeError(error)], samples: receipts } };
  }
}

async function boundedBody(response) {
  const chunks = []; let size = 0;
  for await (const chunk of response.body) {
    size += chunk.length; need(size <= LIMIT, 'api_size_limit'); chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

export function githubApi(token) {
  const base = `https://api.github.com/repos/${REPOSITORY}`;
  const download = async (path, options = {}) => {
    const response = await fetch(base + path, { method: options.method ?? 'GET', signal: AbortSignal.timeout(30000), redirect: 'manual',
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2026-03-10', 'Content-Type': 'application/json' },
      ...(options.body ? { body: JSON.stringify(options.body) } : {}) });
    if (response.status === 302 && path.endsWith('/zip')) {
      const target = new URL(response.headers.get('location'));
      need(target.protocol === 'https:' && !target.username && !target.password, 'unsafe_artifact_redirect');
      // Never forward the repository token to signed artifact storage URLs.
      const artifact = await fetch(target, { signal: AbortSignal.timeout(30000), redirect: 'error' });
      need(artifact.ok, 'artifact_download_failed'); return boundedBody(artifact);
    }
    need(response.ok, 'github_request_failed'); return boundedBody(response);
  };
  return {
    request: async (path, options) => JSON.parse(await download(path, options)),
    sample: async (artifact) => decodeSampleArchive(await download(`/actions/artifacts/${artifact.id}/zip`)),
  };
}

export function decodeSampleArchive(bytes) {
  need(bytes.length <= LIMIT, 'unsafe_sample_archive');
  const script = `import io,json,sys,zipfile,stat
raw=sys.stdin.buffer.read(1048577)
assert len(raw)<=1048576
with zipfile.ZipFile(io.BytesIO(raw)) as z:
 entries=z.infolist()
 assert len(entries)==1
 e=entries[0]
 assert e.filename==e.orig_filename=='sample.json' and e.file_size<=1048576 and not e.flag_bits&1
 assert stat.S_IFMT(e.external_attr>>16) in (0,stat.S_IFREG)
 with z.open(e) as f:
  data=f.read(1048577)
  assert len(data)<=1048576 and not f.read(1)
 json.loads(data)
 sys.stdout.buffer.write(data)
`;
  const output = spawnSync('python3', ['-c', script], { input: bytes, maxBuffer: LIMIT, timeout: 30000 });
  need(output.status === 0, 'unsafe_sample_archive'); return JSON.parse(output.stdout);
}

function local(command, args, cwd = process.cwd()) {
  const result = spawnSync(command, args, { cwd, encoding: 'utf8', maxBuffer: LIMIT, timeout: 30000 });
  need(result.status === 0, 'local_identity_failed'); return result.stdout.trim();
}

export function verifyCheckout(cwd, sha, scope) {
  need(isSha(sha) && local('git', ['rev-parse', 'HEAD'], cwd) === sha, 'checkout_commit_mismatch');
  need(local('git', ['status', '--porcelain=v1', '--untracked-files=all'], cwd) === '', 'dirty_application_source');
  const committed = spawnSync('git', ['show', `${sha}:${PLAN}`], { cwd, maxBuffer: LIMIT, timeout: 30000 });
  need(committed.status === 0 && readFileSync(resolve(cwd, PLAN)).equals(committed.stdout), 'checkout_plan_mismatch');
  return { plan: selectPlan(committed.stdout, scope), source_identity: {
    application_source_commit: sha, application_source_tree: local('git', ['rev-parse', `${sha}^{tree}`], cwd), test_plan_sha256: sha256(committed.stdout),
  } };
}

export function runCommittedPlan(cwd, plan, config) {
  const env = Object.fromEntries(['PATH', 'HOME', 'JAVA_HOME', 'TMPDIR', 'LANG'].filter(key => process.env[key]).map(key => [key, process.env[key]]));
  for (const command of plan.commands) {
    const [program, ...argv] = commandArguments(command, config);
    const result = spawnSync(program, argv, { cwd, env, stdio: 'inherit', timeout: 20 * 60 * 1000 });
    need(result.status === 0, 'test_execution_failed');
  }
}

function writeJson(path, document) { writeFileSync(path, JSON.stringify(document, null, 2) + '\n', { mode: 0o600 }); }

async function main() {
  const mode = process.argv[2], out = process.env.EXPERIMENT_OUT;
  need(out && ['control', 'attest', 'sample'].includes(mode), 'invalid_mode');
  mkdirSync(out, { recursive: true });
  if (mode === 'sample') {
    const evidence = { version: 1, status: 'inconclusive' };
    try {
      const source = process.env.APPLICATION_SOURCE_COMMIT, configCommit = process.env.CONFIG_COMMIT;
      need(isSha(source) && isSha(configCommit) && process.env.GITHUB_SHA === configCommit && process.env.GITHUB_REPOSITORY === REPOSITORY, 'invalid_sample_context');
      const scope = process.env.SCOPE, side = process.env.SIDE, ordinal = Number(process.env.ORDINAL);
      need(['baseline', 'candidate'].includes(side) && [1, 2, 3].includes(ordinal) && process.env.GITHUB_RUN_ATTEMPT === '1' && positive(Number(process.env.EXPERIMENT_RUN_ID)), 'invalid_sample_context');
      const { plan, source_identity } = verifyCheckout(process.cwd(), source, scope);
      const config = parseConfig(await content(githubApi(process.env.GITHUB_TOKEN), 'gradle.properties', configCommit));
      Object.assign(evidence, { experiment_run_id: Number(process.env.EXPERIMENT_RUN_ID), side, ordinal, run_id: Number(process.env.GITHUB_RUN_ID),
        run_attempt: 1, commit_sha: configCommit, scope, suite: plan.suite, source_identity, config,
        environment_identity: { runner_image: `${process.env.ImageOS}/${process.env.ImageVersion}`, java_version: local('java', ['-XshowSettings:properties', '-version']) || local('javac', ['-version']),
          python_version: local('python3', ['--version']), cache_state: 'cold', database_fixture: plan.fixture,
          cpu_memory_profile: `${cpus().length}cpu/${Math.round(totalmem() / 1024 ** 3)}GiB`, dependency_mode: plan.dependency_mode, config_catalog_hash: sha256(configCatalog) } });
      need(process.env.ImageOS && process.env.ImageVersion, 'runner_image_unavailable');
      runCommittedPlan(process.cwd(), plan, config);
      verifyCheckout(process.cwd(), source, scope);
      evidence.status = 'success';
    } catch (error) { evidence.error = safeError(error); process.exitCode = 1; }
    writeJson(resolve(out, 'sample.json'), evidence); return;
  }
  const c = { repository: process.env.GITHUB_REPOSITORY, baseline_ref: process.env.BASELINE_REF, candidate_ref: process.env.CANDIDATE_REF,
    scope: process.env.SCOPE, reason: process.env.REASON, experiment_run_id: Number(process.env.GITHUB_RUN_ID), experiment_run_attempt: Number(process.env.GITHUB_RUN_ATTEMPT),
    controller_sha: process.env.GITHUB_SHA, controller_ref: process.env.GITHUB_REF };
  const api = githubApi(process.env.GITHUB_TOKEN);
  let result;
  if (mode === 'control') {
    result = await executeExperiment(api, c, { deadline: Date.now() + 3300_000, onReceipt: receipts => writeJson(resolve(out, 'receipts.json'), receipts) });
    writeJson(resolve(out, 'receipts.json'), result.receipts);
  } else {
    result = await collectExperiment(api, c, JSON.parse(readFileSync(resolve(out, 'receipts.json'))));
    if (result.manifest) writeJson(resolve(out, 'experiment-manifest.json'), result.manifest);
  }
  writeJson(resolve(out, `diagnostic-${mode}.json`), result.diagnostic);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY,
    `## Pipeline benchmark experiment\n\n상태: ${result.diagnostic.status}\n\n${runUrl(c.experiment_run_id)}\n\n` +
    `scope: ${['ci', 'test'].includes(c.scope) ? c.scope : 'invalid'}; baseline: ${isSha(c.baseline_ref) ? c.baseline_ref : 'invalid'}; candidate: ${isSha(c.candidate_ref) ? c.candidate_ref : 'invalid'}\n\n` +
    `진단: ${result.diagnostic.errors.join(', ') || 'six verified samples; Toolkit 비교 필요'}\n`);
  if (!result.manifest) process.exitCode = 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(() => { console.error('pipeline_experiment_failed'); process.exitCode = 1; });
