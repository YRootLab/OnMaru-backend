import assert from 'node:assert/strict';
import { chmod, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const repoRoot = path.resolve(import.meta.dirname, '../..');
const helper = path.join(
  repoRoot,
  'skills/onmaru-ci-benchmark-experiment/scripts/run_experiment.py',
);
const toolkitRef = '9c6f0033a5ec2429085b29d56ebdb3caca94bbcd';

async function fakeToolkit() {
  const directory = await mkdtemp(path.join(tmpdir(), 'onmaru-experiment-skill-'));
  const executable = path.join(directory, 'pipeline-toolkit');
  const argvPath = path.join(directory, 'argv.json');
  await writeFile(
    executable,
    `#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

Path(os.environ["FAKE_ARGV_PATH"]).write_text(json.dumps(sys.argv[1:]))
sys.stdout.write(os.environ.get("FAKE_STDOUT", "{}"))
sys.stderr.write(os.environ.get("FAKE_STDERR", ""))
raise SystemExit(int(os.environ.get("FAKE_EXIT", "0")))
`,
  );
  await chmod(executable, 0o755);
  return { executable, argvPath };
}

function invoke(executable, argvPath, args = [], extraEnv = {}) {
  return spawnSync('python3', [helper, ...args], {
    cwd: repoRoot,
    encoding: 'utf8',
    env: {
      ...process.env,
      ONMARU_PIPELINE_TOOLKIT_BIN: executable,
      ONMARU_PIPELINE_TOOLKIT_REF: toolkitRef,
      FAKE_ARGV_PATH: argvPath,
      ...extraEnv,
    },
  });
}

test('unspecified action executes only the Toolkit dry-run with literal argv', async () => {
  const fake = await fakeToolkit();
  const reason = 'workers 비교; $(touch should-not-run)';
  const result = invoke(fake.executable, fake.argvPath, [
    '--scope', 'test',
    '--reason', reason,
  ], { FAKE_STDOUT: '{"dry_run":true}\n' });

  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(JSON.parse(await readFile(fake.argvPath, 'utf8')), [
    'experiment', 'dry-run', '--repo-root', repoRoot, '--scope', 'test', '--reason', reason,
  ]);
  assert.deepEqual(JSON.parse(result.stdout), { dry_run: true });
});

test('dispatch requires a current-call authorization flag and never invokes Toolkit without it', async () => {
  const fake = await fakeToolkit();
  const denied = invoke(fake.executable, fake.argvPath, [
    'dispatch', '--scope', 'ci', '--reason', 'cache 비교',
  ]);

  assert.equal(denied.status, 2);
  assert.match(denied.stdout, /명시적인 현재 대화의 dispatch 요청/);
  await assert.rejects(readFile(fake.argvPath, 'utf8'), { code: 'ENOENT' });

  const allowed = invoke(fake.executable, fake.argvPath, [
    'dispatch', '--authorize-dispatch', '--scope', 'ci', '--reason', 'cache 비교',
  ], { FAKE_STDOUT: '{"experiment_run":{"id":42}}\n' });
  assert.equal(allowed.status, 0, allowed.stderr);
  assert.deepEqual(JSON.parse(await readFile(fake.argvPath, 'utf8')), [
    'experiment', 'dispatch', '--repo-root', repoRoot, '--scope', 'ci', '--reason', 'cache 비교',
  ]);
});

test('wait and compare forward only their operation-specific arguments', async () => {
  const fake = await fakeToolkit();
  const receipt = '/tmp/receipt.json';
  const waited = invoke(fake.executable, fake.argvPath, [
    'wait', '--receipt', receipt, '--timeout', '120', '--poll-interval', '2',
  ]);
  assert.equal(waited.status, 0, waited.stderr);
  assert.deepEqual(JSON.parse(await readFile(fake.argvPath, 'utf8')), [
    'experiment', 'wait', '--receipt', receipt, '--timeout', '120.0', '--poll-interval', '2.0',
  ]);

  const input = '/tmp/collection.json';
  const compared = invoke(fake.executable, fake.argvPath, [
    'compare', '--input', input, '--format', 'markdown',
  ]);
  assert.equal(compared.status, 0, compared.stderr);
  assert.deepEqual(JSON.parse(await readFile(fake.argvPath, 'utf8')), [
    'experiment', 'compare', '--input', input, '--format', 'markdown',
  ]);
});

test('Toolkit exit 2 is preserved while raw stderr and secret-shaped JSON values are suppressed', async () => {
  const fake = await fakeToolkit();
  const result = invoke(fake.executable, fake.argvPath, [], {
    FAKE_EXIT: '2',
    FAKE_STDERR: 'Authorization: Bearer raw-secret\n',
    FAKE_STDOUT: '{"error":{"code":"dirty_tree"},"token":"stdout-secret"}\n',
  });

  assert.equal(result.status, 2);
  assert.equal(result.stderr, '');
  assert.doesNotMatch(result.stdout, /raw-secret|stdout-secret/);
  assert.deepEqual(JSON.parse(result.stdout), {
    error: { code: 'dirty_tree' },
    token: '[REDACTED]',
  });
});

test('exit-zero inconclusive remains exit zero and oversized output fails closed', async () => {
  const fake = await fakeToolkit();
  const inconclusive = invoke(fake.executable, fake.argvPath, [], {
    FAKE_STDOUT: '{"verdict":"inconclusive","exclusions":["missing_sample"]}\n',
  });
  assert.equal(inconclusive.status, 0);
  assert.equal(JSON.parse(inconclusive.stdout).verdict, 'inconclusive');

  const oversized = invoke(fake.executable, fake.argvPath, [], {
    FAKE_STDOUT: JSON.stringify({ payload: 'x'.repeat(70_000) }),
  });
  assert.equal(oversized.status, 2);
  assert.ok(Buffer.byteLength(oversized.stdout) < 4_096);
  assert.match(oversized.stdout, /toolkit_output_too_large/);
});

test('configured Toolkit ref must equal the repository immutable pin', async () => {
  const fake = await fakeToolkit();
  const result = invoke(fake.executable, fake.argvPath, [], {
    ONMARU_PIPELINE_TOOLKIT_REF: 'a'.repeat(40),
  });

  assert.equal(result.status, 2);
  assert.match(result.stdout, new RegExp(toolkitRef));
  await assert.rejects(readFile(fake.argvPath, 'utf8'), { code: 'ENOENT' });
});
