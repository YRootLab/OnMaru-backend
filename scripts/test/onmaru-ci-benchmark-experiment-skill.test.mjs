import assert from 'node:assert/strict';
import { chmod, mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
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

async function fakeToolkit(commitId = toolkitRef) {
  const directory = await mkdtemp(path.join(tmpdir(), 'onmaru-experiment-skill-'));
  const interpreter = spawnSync('which', ['python3'], { encoding: 'utf8' }).stdout.trim();
  const executable = path.join(directory, 'pipeline-toolkit');
  const argvPath = path.join(directory, 'argv.json');
  const sitePackages = path.join(directory, 'site-packages');
  const packageDirectory = path.join(sitePackages, 'pipeline_toolkit');
  const distInfo = path.join(sitePackages, 'onmaru_pipeline_toolkit-0.dist-info');
  await mkdir(packageDirectory, { recursive: true });
  await mkdir(distInfo, { recursive: true });
  await writeFile(path.join(packageDirectory, '__init__.py'), '');
  await writeFile(path.join(packageDirectory, 'cli.py'), `
import json
import os
import sys
import time
from pathlib import Path

def main():
    Path(os.environ["FAKE_ARGV_PATH"]).write_text(json.dumps(sys.argv[1:]))
    if os.environ.get("FAKE_STREAM_BYTES"):
        sys.stdout.write("x" * int(os.environ["FAKE_STREAM_BYTES"]))
        sys.stdout.flush()
    if os.environ.get("FAKE_HANG"):
        time.sleep(float(os.environ["FAKE_HANG"]))
    sys.stdout.write(os.environ.get("FAKE_STDOUT", "{}"))
    sys.stderr.write(os.environ.get("FAKE_STDERR", ""))
    return int(os.environ.get("FAKE_EXIT", "0"))
`);
  await writeFile(path.join(distInfo, 'METADATA'), 'Metadata-Version: 2.1\nName: onmaru-pipeline-toolkit\nVersion: 0\n');
  await writeFile(path.join(distInfo, 'direct_url.json'), JSON.stringify({
    url: 'https://github.com/YRootLab/OnMaru-backend-ci-toolkit.git',
    vcs_info: {
      vcs: 'git',
      commit_id: commitId,
    },
  }));
  await writeFile(executable, `#!${interpreter}\nfrom pipeline_toolkit.cli import main\nraise SystemExit(main())\n`);
  await chmod(executable, 0o755);
  return { executable, argvPath, sitePackages };
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
      PYTHONPATH: extraEnv.PYTHONPATH ?? path.join(path.dirname(executable), 'site-packages'),
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

test('a matching ref environment string alone cannot prove the installed binary pin', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'onmaru-unverified-toolkit-'));
  const executable = path.join(directory, 'unverified-cli');
  const argvPath = path.join(directory, 'argv.json');
  await writeFile(executable, '#!/usr/bin/env python3\n');
  await chmod(executable, 0o755);
  const result = invoke(executable, argvPath);

  assert.equal(result.status, 2);
  assert.match(result.stdout, /toolkit_install_unverified/);
  await assert.rejects(readFile(argvPath, 'utf8'), { code: 'ENOENT' });
});

test('an installed Toolkit from an older commit is rejected before invocation', async () => {
  const fake = await fakeToolkit('a'.repeat(40));
  const result = invoke(fake.executable, fake.argvPath);

  assert.equal(result.status, 2);
  assert.match(result.stdout, /toolkit_install_unverified/);
  await assert.rejects(readFile(fake.argvPath, 'utf8'), { code: 'ENOENT' });
});

test('dispatch timeout and output overflow stop the child and forbid retry', async () => {
  const fake = await fakeToolkit();
  const started = Date.now();
  const timeout = invoke(fake.executable, fake.argvPath, [
    'dispatch', '--authorize-dispatch', '--reason', 'timeout test',
  ], {
    FAKE_HANG: '5',
    ONMARU_PIPELINE_TOOLKIT_TIMEOUT_SECONDS: '0.1',
  });
  assert.equal(timeout.status, 2);
  assert.ok(Date.now() - started < 2_000);
  assert.match(timeout.stdout, /dispatch 상태가 모호.*절대 다시 dispatch하지 마세요/);

  const overflow = invoke(fake.executable, fake.argvPath, [
    'dispatch', '--authorize-dispatch', '--reason', 'overflow test',
  ], {
    FAKE_STREAM_BYTES: '70000',
    FAKE_HANG: '5',
  });
  assert.equal(overflow.status, 2);
  assert.match(overflow.stdout, /dispatch 상태가 모호.*절대 다시 dispatch하지 마세요/);

  const dryRunTimeout = invoke(fake.executable, fake.argvPath, [], {
    FAKE_HANG: '5',
    ONMARU_PIPELINE_TOOLKIT_TIMEOUT_SECONDS: '0.1',
  });
  assert.equal(dryRunTimeout.status, 2);
  assert.match(dryRunTimeout.stdout, /toolkit_timeout/);
  assert.doesNotMatch(dryRunTimeout.stdout, /다시 dispatch/);
});

test('redacts GitHub PATs, secret-like keys, and key-value secrets inside strings', async () => {
  const fake = await fakeToolkit();
  const result = invoke(fake.executable, fake.argvPath, [], {
    FAKE_STDOUT: JSON.stringify({
      api_key: 'api-secret',
      credential_value: 'credential-secret',
      note: 'github_pat_abcdefghijklmnop api_key=inline-secret password=hunter2',
    }),
  });

  assert.equal(result.status, 0);
  assert.doesNotMatch(result.stdout, /api-secret|credential-secret|abcdefghijklmnop|inline-secret|hunter2/);
  assert.deepEqual(JSON.parse(result.stdout), {
    api_key: '[REDACTED]',
    credential_value: '[REDACTED]',
    note: 'github_pat_[REDACTED] api_key=[REDACTED] password=[REDACTED]',
  });
});
