import assert from 'node:assert/strict';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { redactUrl } from '../capture-odii-fixtures.mjs';
import { validateOdiiFixtureSet } from '../lib/odii-fixture-validation.mjs';

test('rejects manifest values that leak service keys', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'odii-fixtures-'));
  try {
    const fixtureDir = path.join(dir, 'fixtures');
    await mkdir(fixtureDir, { recursive: true });
    await writeFile(path.join(fixtureDir, 'normal.json'), '{}\n');
    await writeFile(
      path.join(dir, 'manifest.json'),
      JSON.stringify({
        requiredScenarios: ['normal'],
        captures: [
          {
            scenario: 'normal',
            fixture: 'normal.json',
            sha256: '44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a',
            endpoint: 'https://apis.data.go.kr/B551011/Odii/storyBasedList?serviceKey=abc',
          },
        ],
      }),
    );

    await assert.rejects(
      () => validateOdiiFixtureSet({ rootDir: dir, manifestPath: path.join(dir, 'manifest.json'), fixtureDir }),
      /secret-like value/,
    );
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('redacts service keys from captured request URLs', () => {
  assert.equal(
    redactUrl('https://x.test/path?MobileOS=ETC&serviceKey=abc&_type=json'),
    'https://x.test/path?MobileOS=ETC&_type=json',
  );
});

test('rejects fixture JSON fields that leak service keys', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'odii-fixtures-'));
  try {
    const fixtureDir = path.join(dir, 'fixtures');
    await mkdir(fixtureDir, { recursive: true });
    await writeFile(path.join(fixtureDir, 'leaky.json'), '{ "serviceKey": "abc" }\n');
    await writeFile(
      path.join(dir, 'manifest.json'),
      JSON.stringify({
        requiredScenarios: ['leaky'],
        captures: [
          {
            scenario: 'leaky',
            fixture: 'leaky.json',
            sha256: 'abc81c4d1546791e8674b24b94a06dbbb449ed5b79eff3b21a6e7d831acf2380',
          },
        ],
      }),
    );

    await assert.rejects(
      () => validateOdiiFixtureSet({ rootDir: dir, manifestPath: path.join(dir, 'manifest.json'), fixtureDir }),
      /secret-like value/,
    );
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('rejects long URL-encoded token values in manifest URLs', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'odii-fixtures-'));
  try {
    const fixtureDir = path.join(dir, 'fixtures');
    await mkdir(fixtureDir, { recursive: true });
    await writeFile(path.join(fixtureDir, 'normal.json'), '{}\n');
    await writeFile(
      path.join(dir, 'manifest.json'),
      JSON.stringify({
        requiredScenarios: ['normal'],
        captures: [
          {
            scenario: 'normal',
            fixture: 'normal.json',
            sha256: 'ca3d163bab055381827226140568f3bef7eaac187cebd76878e0b63e9e442356',
            redactedUrl:
              'https://apis.data.go.kr/B551011/Odii/storySearchList?MobileOS=ETC&token=abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghij%2Bklmnopqrstuv',
          },
        ],
      }),
    );

    await assert.rejects(
      () => validateOdiiFixtureSet({ rootDir: dir, manifestPath: path.join(dir, 'manifest.json'), fixtureDir }),
      /secret-like value/,
    );
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('rejects fixture hash mismatches', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'odii-fixtures-'));
  try {
    const fixtureDir = path.join(dir, 'fixtures');
    await mkdir(fixtureDir, { recursive: true });
    await writeFile(path.join(fixtureDir, 'normal.json'), '{}\n');
    await writeFile(
      path.join(dir, 'manifest.json'),
      JSON.stringify({
        requiredScenarios: ['normal'],
        captures: [
          {
            scenario: 'normal',
            fixture: 'normal.json',
            sha256: '0000000000000000000000000000000000000000000000000000000000000000',
          },
        ],
      }),
    );

    await assert.rejects(
      () => validateOdiiFixtureSet({ rootDir: dir, manifestPath: path.join(dir, 'manifest.json'), fixtureDir }),
      /fixture hash mismatch for normal\.json/,
    );
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('rejects manifests missing required scenarios', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'odii-fixtures-'));
  try {
    const fixtureDir = path.join(dir, 'fixtures');
    await mkdir(fixtureDir, { recursive: true });
    await writeFile(path.join(fixtureDir, 'other.json'), '{}\n');
    await writeFile(
      path.join(dir, 'manifest.json'),
      JSON.stringify({
        requiredScenarios: ['normal'],
        captures: [
          {
            scenario: 'other',
            fixture: 'other.json',
            sha256: 'ca3d163bab055381827226140568f3bef7eaac187cebd76878e0b63e9e442356',
          },
        ],
      }),
    );

    await assert.rejects(
      () => validateOdiiFixtureSet({ rootDir: dir, manifestPath: path.join(dir, 'manifest.json'), fixtureDir }),
      /missing required Odii scenarios: normal/,
    );
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
