import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, mkdir, readFile, writeFile, access, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';

const fixtureDir = resolve('scripts/test/fixtures/ci-observability');
const adapter = resolve('scripts/benchmark/ci-observability.py');
const toolkitSha = '9c6f0033a5ec2429085b29d56ebdb3caca94bbcd';
const toolkitPath = process.env.ONMARU_TOOLKIT_SRC ?? (existsSync('/tmp/onmaru-ci-toolkit-9c6f003/src')
  ? '/tmp/onmaru-ci-toolkit-9c6f003/src' : resolve(fixtureDir, 'toolkit_stub'));
const repository = 'YRootLab/OnMaru-backend';

const fixture = async (name) => JSON.parse(await readFile(join(fixtureDir, name), 'utf8'));
const clone = (value) => structuredClone(value);

async function runPython(args, env = {}) {
  return new Promise((done) => {
    const child = spawn('python3', [adapter, ...args], {
      env: { ...process.env, PYTHONPATH: toolkitPath, GITHUB_TOKEN: 'fixture-token', ...env },
    });
    let stdout = '', stderr = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    child.on('close', (code) => done({ code, stdout, stderr }));
  });
}

function zip(entries) {
  const script = `import json,sys,zipfile,io,base64
entries=json.load(sys.stdin)
stream=io.BytesIO()
with zipfile.ZipFile(stream,'w',zipfile.ZIP_DEFLATED) as archive:
  for item in entries:
    info=zipfile.ZipInfo(item['name'])
    info.compress_type=zipfile.ZIP_DEFLATED
    if item.get('symlink'):
      info.create_system=3
      info.external_attr=(0o120777 << 16)
    archive.writestr(info,base64.b64decode(item['data']))
sys.stdout.buffer.write(stream.getvalue())`;
  const input = entries.map(({ name, data, symlink }) => ({
    name, data: Buffer.from(data).toString('base64'), symlink,
  }));
  const result = spawnSync('python3', ['-c', script], { input: JSON.stringify(input), maxBuffer: 20 * 1024 * 1024 });
  assert.equal(result.status, 0, result.stderr.toString());
  return result.stdout;
}

async function withApi(overrides, body) {
  const run = Object.assign(await fixture('run.json'), overrides.run);
  const jobs = Object.assign(await fixture('jobs.json'), overrides.jobs);
  const execution = Object.assign(await fixture('execution.json'), overrides.execution);
  const archive = overrides.archive ?? zip([{ name: 'execution.json', data: JSON.stringify(execution) }]);
  const artifacts = overrides.artifacts ?? [{ id: 300, name: 'module-evidence-catalog', size_in_bytes: archive.length, expired: false }];
  const pages = overrides.pages ?? [jobs];
  const requests = [];
  const signedAuthorization = [];
  let prior = null;
  const server = createServer((req, res) => {
    requests.push(req.url);
    const json = (value) => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(value)); };
    if (req.url === '/repos/YRootLab/OnMaru-backend/actions/runs/731/attempts/2') return json(run);
    if (req.url?.startsWith('/repos/YRootLab/OnMaru-backend/actions/runs/731/attempts/2/jobs?')) {
      const page = Number(new URL(req.url, 'http://localhost').searchParams.get('page'));
      return json(pages[page - 1] ?? { total_count: jobs.total_count, jobs: [] });
    }
    if (req.url?.startsWith('/repos/YRootLab/OnMaru-backend/actions/runs/731/artifacts?')) {
      const page = Number(new URL(req.url, 'http://localhost').searchParams.get('page'));
      return json(page === 1 ? { total_count: artifacts.length, artifacts } : { total_count: artifacts.length, artifacts: [] });
    }
    if (req.url === '/repos/YRootLab/OnMaru-backend/actions/artifacts/300/zip') {
      if (overrides.archiveRedirect) {
        res.statusCode = 302;
        res.setHeader('Location', typeof overrides.archiveRedirect === 'string'
          ? overrides.archiveRedirect : `http://127.0.0.1:${server.address().port}/signed/module.zip?sig=fixture`);
        res.end(); return;
      }
      res.setHeader('Content-Type', 'application/zip'); res.end(archive); return;
    }
    if (req.url === '/signed/module.zip?sig=fixture') {
      signedAuthorization.push(req.headers.authorization);
      res.setHeader('Content-Type', 'application/zip'); res.end(archive); return;
    }
    if (req.url?.startsWith('/repos/YRootLab/OnMaru-backend/actions/artifacts?')) {
      return json({ total_count: prior ? 1 : 0, artifacts: prior ? [{
        id: 900, name: 'ci-observability-diagnostic-731-2', size_in_bytes: prior.length,
        expired: false, workflow_run: { id: 500 },
      }] : [] });
    }
    if (req.url === '/repos/YRootLab/OnMaru-backend/actions/runs/500') return json({
      id: 500, name: 'CI Observability', path: '.github/workflows/ci-observability.yml',
      status: 'completed', repository: { full_name: repository },
    });
    if (req.url === '/repos/YRootLab/OnMaru-backend/actions/artifacts/900/zip' && prior) {
      if (overrides.archiveRedirect) {
        res.statusCode = 302;
        res.setHeader('Location', `http://127.0.0.1:${server.address().port}/signed/replay.zip?sig=fixture`);
        res.end(); return;
      }
      res.setHeader('Content-Type', 'application/zip'); res.end(prior); return;
    }
    if (req.url === '/signed/replay.zip?sig=fixture' && prior) {
      signedAuthorization.push(req.headers.authorization);
      res.setHeader('Content-Type', 'application/zip'); res.end(prior); return;
    }
    res.statusCode = 404; json({ message: 'not found' });
  });
  await new Promise((ready) => server.listen(0, '127.0.0.1', ready));
  const directory = await mkdtemp(join(tmpdir(), 'onmaru-observability-'));
  try {
    return await body({
      directory, requests, signedAuthorization, setPrior: (value) => { prior = value; },
      apiBase: `http://127.0.0.1:${server.address().port}`,
      args: ['collect', '--repository', repository, '--run-id', '731', '--attempt', '2',
        '--workflow', 'Module Benchmark', '--api-base-url', `http://127.0.0.1:${server.address().port}`,
        '--out-dir', directory],
    });
  } finally {
    server.close();
    await rm(directory, { recursive: true, force: true });
  }
}

test('post-run workflow is completed-only, trusted, least privilege, and diagnostics survive export failure', async () => {
  const workflow = await readFile('.github/workflows/ci-observability.yml', 'utf8');
  const ci = await readFile('.github/workflows/ci.yml', 'utf8');
  assert.match(workflow, /workflow_run:\s*\n\s*workflows:\s*\[CI, Module Benchmark\]/);
  assert.match(workflow, /types:\s*\[completed\]/);
  assert.match(workflow, /workflow_dispatch:/);
  assert.match(workflow, /run_id:/);
  assert.match(workflow, /attempt:/);
  assert.match(workflow, /contents: read/);
  assert.match(workflow, /actions: read/);
  assert.doesNotMatch(workflow, /\w+: write|secrets: inherit|pull_request_target/);
  assert.match(workflow, /ref: \$\{\{ github\.event\.repository\.default_branch \}\}/);
  assert.match(workflow, /environment: ci-observability/);
  assert.match(workflow, /if: always\(\)/);
  assert.match(workflow, /ci-observability-diagnostic-/);
  assert.match(workflow, new RegExp(toolkitSha));
  assert.doesNotMatch(ci, /ci-observability|OTLP_ENDPOINT|OTLP_HEADERS/);
});

test('a later post-run execution restores the checkpoint and observation time from its diagnostic artifact', async () => {
  await withApi({ archiveRedirect: true }, async ({ directory, args, apiBase, setPrior, signedAuthorization }) => {
    assert.equal((await runPython(args)).code, 0);
    let delivered = 0;
    const receiver = createServer((req, res) => {
      delivered++;
      req.resume();
      res.setHeader('Content-Type', 'application/json');
      res.end('{}');
    });
    await new Promise((ready) => receiver.listen(0, '127.0.0.1', ready));
    try {
      const endpoint = `http://127.0.0.1:${receiver.address().port}`;
      assert.equal((await runPython(['export', '--dir', directory, '--endpoint', endpoint])).code, 0);
      const original = JSON.parse(await readFile(join(directory, 'collection.json'), 'utf8'));
      setPrior(zip([
        { name: 'collection.json', data: await readFile(join(directory, 'collection.json')) },
        { name: 'replay.sqlite', data: await readFile(join(directory, 'replay.sqlite')) },
      ]));
      const next = join(directory, 'next');
      await mkdir(next);
      await writeFile(join(next, 'evidence.json'), await readFile(join(directory, 'evidence.json')));
      await writeFile(join(next, 'diagnostics.md'), await readFile(join(directory, 'diagnostics.md')));
      await writeFile(join(next, 'collection.json'), JSON.stringify({ ...original, observed_at_ns: original.observed_at_ns + 1_000_000 }));
      const count = delivered;
      const replay = await runPython(['export', '--dir', next, '--endpoint', endpoint,
        '--restore-from-api', apiBase], { GITHUB_TOKEN: 'fixture-token' });
      assert.equal(replay.code, 0, replay.stderr);
      assert.equal(JSON.parse(await readFile(join(next, 'export-result.json'), 'utf8')).status, 'duplicate');
      assert.equal(delivered, count);
      assert.equal(JSON.parse(await readFile(join(next, 'collection.json'), 'utf8')).observed_at_ns, original.observed_at_ns);
      assert.deepEqual(signedAuthorization, [undefined, undefined]);
    } finally {
      receiver.close();
    }
  });
});

test('collect follows the signed archive location without forwarding the GitHub token', async () => {
  await withApi({ archiveRedirect: true }, async ({ directory, args, requests, signedAuthorization }) => {
    const result = await runPython(args);
    assert.equal(result.code, 0, result.stderr);
    assert.equal(JSON.parse(await readFile(join(directory, 'evidence.json'), 'utf8')).artifact_quality.status, 'complete');
    assert.ok(requests.includes('/signed/module.zip?sig=fixture'));
    assert.deepEqual(signedAuthorization, [undefined]);
  });
});

test('collect rejects an archive redirect outside the trusted storage boundary', async () => {
  for (const location of [
    'https://productionresults.blob.core.windows.net.evil.example/archive.zip?sig=fixture',
    'https://user:password@productionresults.blob.core.windows.net/archive.zip',
    'http://productionresults.blob.core.windows.net/archive.zip',
    'https://productionresults.blob.core.windows.net/archive.zip#fragment',
  ]) {
    await withApi({ archiveRedirect: location }, async ({ directory, args }) => {
      const result = await runPython(args);
      assert.notEqual(result.code, 0);
      await assert.rejects(access(join(directory, 'evidence.json')));
    });
  }
});

test('collect normalizes bounded module evidence and never retains or runs its command', async () => {
  await withApi({}, async ({ directory, args }) => {
    const result = await runPython(args);
    assert.equal(result.code, 0, result.stderr);
    const evidence = JSON.parse(await readFile(join(directory, 'evidence.json'), 'utf8'));
    assert.equal(evidence.run.conclusion, 'success');
    assert.equal(evidence.run.attempt, 2);
    assert.deepEqual(evidence.modules.map(({ module_id, wall_clock_seconds }) => [module_id, wall_clock_seconds]), [['catalog', 8]]);
    assert.equal(evidence.artifact_quality.status, 'complete');
    assert.doesNotMatch(JSON.stringify(evidence), /onmaru-ci-observability-command-must-not-run|fixture-token/);
    await assert.rejects(access('/tmp/onmaru-ci-observability-command-must-not-run'));
  });
});

test('CI collection records jobs-only quality without requesting module archives', async () => {
  const run = { ...await fixture('run.json'), name: 'CI', path: '.github/workflows/ci.yml', event: 'pull_request' };
  await withApi({ run }, async ({ directory, args, requests }) => {
    args[args.indexOf('--workflow') + 1] = 'CI';
    const result = await runPython(args);
    assert.equal(result.code, 0, result.stderr);
    const evidence = JSON.parse(await readFile(join(directory, 'evidence.json'), 'utf8'));
    assert.equal(evidence.artifact_quality.status, 'unavailable');
    assert.deepEqual(evidence.modules, []);
    assert.ok(!requests.some((path) => path.includes('/artifacts?')));
  });
});

test('collection redacts credential-shaped job text before retaining normalized evidence', async () => {
  const source = (await fixture('jobs.json')).jobs[0];
  await withApi({ jobs: { jobs: [{ ...source, name: 'module-test (catalog) token=fixture-sensitive' }] }, artifacts: [] }, async ({ directory, args }) => {
    const result = await runPython(args);
    assert.equal(result.code, 0, result.stderr);
    assert.doesNotMatch(await readFile(join(directory, 'evidence.json'), 'utf8'), /fixture-sensitive/);
    assert.doesNotMatch(await readFile(join(directory, 'diagnostics.md'), 'utf8'), /fixture-sensitive/);
  });
});

test('failure and cancellation retain source conclusions', async () => {
  for (const conclusion of ['failure', 'cancelled']) {
    await withApi({ run: { conclusion }, jobs: { jobs: [{ ...(await fixture('jobs.json')).jobs[0], conclusion }] } }, async ({ directory, args }) => {
      const result = await runPython(args);
      assert.equal(result.code, 0, result.stderr);
      const evidence = JSON.parse(await readFile(join(directory, 'evidence.json'), 'utf8'));
      assert.equal(evidence.run.conclusion, conclusion);
    });
  }
});

test('collect rejects run attempt mismatch and foreign source metadata', async () => {
  for (const run of [{ run_attempt: 1 }, { repository: { full_name: 'someone/else' } }, { status: 'in_progress' }, { name: 'Other' }]) {
    await withApi({ run }, async ({ directory, args }) => {
      const result = await runPython(args);
      assert.notEqual(result.code, 0);
      await assert.rejects(access(join(directory, 'evidence.json')));
    });
  }
});

test('collect follows exact attempt job pages and rejects a changed page count', async () => {
  const base = (await fixture('jobs.json')).jobs[0];
  const first = Array.from({ length: 100 }, (_, index) => ({ ...base, id: 1000 + index, name: `job ${index}` }));
  const second = [{ ...base, id: 1100 }];
  await withApi({ artifacts: [], pages: [{ total_count: 101, jobs: first }, { total_count: 101, jobs: second }] }, async ({ directory, args, requests }) => {
    const result = await runPython(args);
    assert.equal(result.code, 0, result.stderr);
    assert.equal(JSON.parse(await readFile(join(directory, 'evidence.json'), 'utf8')).jobs.length, 101);
    assert.ok(requests.some((path) => path.includes('/attempts/2/jobs?per_page=100&page=2')));
  });
  await withApi({ artifacts: [], pages: [{ total_count: 101, jobs: first }, { total_count: 102, jobs: second }] }, async ({ args }) => {
    assert.notEqual((await runPython(args)).code, 0);
  });
});

test('collect rejects unsafe archive members and metadata limits', async () => {
  const execution = JSON.stringify(await fixture('execution.json'));
  const cases = [
    zip([{ name: '../execution.json', data: execution }]),
    zip([{ name: 'execution.json', data: execution, symlink: true }]),
    zip([{ name: 'execution.json', data: execution }, { name: 'execution.json', data: execution }]),
    zip([{ name: 'execution.json', data: 'x'.repeat(1024 * 1024 + 1) }]),
  ];
  for (const archive of cases) {
    await withApi({ archive }, async ({ directory, args }) => {
      assert.notEqual((await runPython(args)).code, 0);
      await assert.rejects(access(join(directory, 'evidence.json')));
    });
  }
  await withApi({ artifacts: [{ id: 300, name: 'module-evidence-catalog', size_in_bytes: 20 * 1024 * 1024, expired: false }] }, async ({ args }) => {
    assert.notEqual((await runPython(args)).code, 0);
  });
});

test('export keeps source conclusion on HTTP failure and suppresses duplicate digest replay', async () => {
  await withApi({}, async ({ directory, args }) => {
    assert.equal((await runPython(args)).code, 0);
    let status = 401, delivered = 0;
    const receiver = createServer((req, res) => {
      delivered++;
      req.resume();
      res.statusCode = status;
      res.setHeader('Content-Type', 'application/json');
      res.end('{}');
    });
    await new Promise((ready) => receiver.listen(0, '127.0.0.1', ready));
    try {
      const endpoint = `http://127.0.0.1:${receiver.address().port}`;
      const exportArgs = ['export', '--dir', directory, '--endpoint', endpoint];
      const failed = await runPython(exportArgs);
      assert.equal(failed.code, 0, failed.stderr);
      const failure = JSON.parse(await readFile(join(directory, 'export-result.json'), 'utf8'));
      assert.equal(failure.status, 'failed');
      assert.equal(failure.ci_conclusion, 'success');
      const replayState = JSON.parse(await readFile(join(directory, 'replay-state.json'), 'utf8'));
      assert.match(replayState.identity_key, /^[0-9a-f]{64}$/);
      assert.equal(replayState.evidence_digest, JSON.parse(await readFile(join(directory, 'evidence.json'), 'utf8')).evidence_digest);
      assert.doesNotMatch(JSON.stringify(replayState), /fixture-token|127\.0\.0\.1|OTLP/);
      status = 200;
      assert.equal((await runPython(exportArgs)).code, 0);
      const exported = JSON.parse(await readFile(join(directory, 'export-result.json'), 'utf8'));
      assert.equal(exported.status, 'exported');
      const afterFirst = delivered;
      assert.equal((await runPython(exportArgs)).code, 0);
      const duplicate = JSON.parse(await readFile(join(directory, 'export-result.json'), 'utf8'));
      assert.equal(duplicate.status, 'duplicate');
      assert.equal(delivered, afterFirst);
    } finally {
      receiver.close();
    }
  });
});

test('CI and Module Benchmark exports use distinct trusted workflow labels and pipeline names', async () => {
  for (const [source, label] of [['CI', 'ci'], ['Module Benchmark', 'module_benchmark']]) {
    const run = source === 'CI' ? { name: 'CI', path: '.github/workflows/ci.yml', event: 'pull_request' } : {};
    await withApi({ run }, async ({ directory, args }) => {
      args[args.indexOf('--workflow') + 1] = source;
      assert.equal((await runPython(args)).code, 0);
      const payloads = [];
      const receiver = createServer((req, res) => {
        const chunks = [];
        req.on('data', (chunk) => chunks.push(chunk));
        req.on('end', () => {
          payloads.push({ path: req.url, body: JSON.parse(Buffer.concat(chunks).toString()) });
          res.setHeader('Content-Type', 'application/json');
          res.end('{}');
        });
      });
      await new Promise((ready) => receiver.listen(0, '127.0.0.1', ready));
      try {
        const endpoint = `http://127.0.0.1:${receiver.address().port}`;
        const result = await runPython(['export', '--dir', directory, '--endpoint', endpoint]);
        assert.equal(result.code, 0, result.stderr);
        const metrics = payloads.find(({ path }) => path === '/v1/metrics')?.body;
        const traces = payloads.find(({ path }) => path === '/v1/traces')?.body;
        assert.ok(metrics, 'missing metric delivery');
        assert.ok(traces, 'missing trace delivery');
        const labels = metrics.resourceMetrics.flatMap((resource) => resource.scopeMetrics)
          .flatMap((scope) => scope.metrics)
          .flatMap((metric) => [...(metric.histogram?.dataPoints ?? []), ...(metric.gauge?.dataPoints ?? [])])
          .flatMap((point) => point.attributes)
          .filter((attribute) => attribute.key === 'workflow')
          .map((attribute) => attribute.value.stringValue);
        assert.deepEqual([...new Set(labels)], [label]);
        const names = traces.resourceSpans.flatMap((resource) => resource.scopeSpans)
          .flatMap((scope) => scope.spans)
          .flatMap((span) => span.attributes)
          .filter((attribute) => attribute.key === 'cicd.pipeline.name')
          .map((attribute) => attribute.value.stringValue);
        assert.deepEqual([...new Set(names)], [label]);
      } finally {
        receiver.close();
      }
    });
  }
});
