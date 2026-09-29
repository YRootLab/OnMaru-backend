import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

test('declares four Spring API shard modules with explicit Gradle shard properties', async () => {
  const catalog = JSON.parse(await readFile('.github/benchmark-modules.yml', 'utf8'));
  const ids = catalog.modules.filter((module) => module.id.startsWith('spring-api-')).map((module) => module.id).sort();
  assert.deepEqual(ids, ['spring-api-postgres-audio', 'spring-api-postgres-catalog', 'spring-api-postgres-other', 'spring-api-unit-contract']);
  for (const module of catalog.modules.filter((module) => module.id.startsWith('spring-api-'))) {
    assert.match(module.test_command, /:apps:spring-api:test --no-daemon/);
    assert.match(module.test_command, /-Ponmaru\.test\.shard=/);
    assert.equal(module.resource_profile, 'heavy');
  }
});

test('defines shard filters in the Spring API Gradle test task', async () => {
  const build = await readFile('apps/spring-api/build.gradle.kts', 'utf8');
  for (const shard of ['unit-contract', 'postgres-catalog', 'postgres-audio', 'postgres-other']) {
    assert.match(build, new RegExp(`['\"]${shard}['\"]`));
  }
  assert.match(build, /onmaru\.test\.shard/);
  assert.match(build, /testing\.postgres/);
});
