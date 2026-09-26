import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

import { selectCiLanes } from '../ci/select-ci-lanes.mjs';

test('selects every lane for workflow and Gradle convention changes', () => {
  assert.deepEqual(selectCiLanes(['.github/workflows/ci.yml']), {
    hygiene: true,
    java: true,
    contract: true,
    ai: true,
  });
  assert.deepEqual(selectCiLanes(['build-logic/ci-performance.gradle.kts']), {
    hygiene: true,
    java: true,
    contract: true,
    ai: true,
  });
});

test('limits a focused AI change to its affected lane plus universal checks', () => {
  assert.deepEqual(selectCiLanes(['ai/src/onmaru_ai/main.py']), {
    hygiene: true,
    java: false,
    contract: true,
    ai: true,
  });
});

test('limits a focused Java change to its affected lane plus universal checks', () => {
  assert.deepEqual(selectCiLanes(['modules/catalog/src/main/java/CatalogService.java']), {
    hygiene: true,
    java: true,
    contract: true,
    ai: false,
  });
});

test('fails closed when an enabled lane is skipped or fails', () => {
  const baseEnvironment = {
    HYGIENE_ENABLED: 'true',
    JAVA_ENABLED: 'false',
    CONTRACT_ENABLED: 'true',
    AI_ENABLED: 'false',
    HYGIENE_RESULT: 'success',
    JAVA_RESULT: 'skipped',
    CONTRACT_RESULT: 'success',
    AI_RESULT: 'skipped',
  };

  const success = spawnSync(process.execPath, ['scripts/ci/verify-ci-lanes.mjs'], {
    encoding: 'utf8',
    env: { ...process.env, ...baseEnvironment },
  });
  assert.equal(success.status, 0, success.stderr);

  const skippedEnabledLane = spawnSync(process.execPath, ['scripts/ci/verify-ci-lanes.mjs'], {
    encoding: 'utf8',
    env: { ...process.env, ...baseEnvironment, JAVA_ENABLED: 'true' },
  });
  assert.notEqual(skippedEnabledLane.status, 0);

  const failedUniversalLane = spawnSync(process.execPath, ['scripts/ci/verify-ci-lanes.mjs'], {
    encoding: 'utf8',
    env: { ...process.env, ...baseEnvironment, CONTRACT_RESULT: 'failure' },
  });
  assert.notEqual(failedUniversalLane.status, 0);
});

test('CI runs a shared-workspace Java lane in parallel with independent lanes', async () => {
  const workflow = await readFile('.github/workflows/ci.yml', 'utf8');

  for (const job of ['plan', 'hygiene', 'java', 'contract', 'ai', 'verify']) {
    assert.match(workflow, new RegExp(`^  ${job}:$`, 'm'));
  }
  assert.match(workflow, /^    needs: plan$/m);
  assert.match(workflow, /^    needs: \[plan, hygiene, java, contract, ai\]$/m);
  assert.match(workflow, /node scripts\/ci\/select-ci-lanes\.mjs/);
  assert.match(workflow, /node scripts\/ci\/verify-ci-lanes\.mjs/);
  assert.match(workflow, /:adapters:tourism-api:test[\s\\]+:modules:insights:test[\s\\]+:modules:catalog:test/);
  assert.match(workflow, /:modules:operations:test[\s\\]+:apps:spring-api:test/);
  assert.match(workflow, /-Ponmaru\.ci\.performance\.enabled=true/);
});
