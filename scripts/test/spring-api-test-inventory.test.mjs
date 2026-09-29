import test from 'node:test';
import assert from 'node:assert/strict';
import { buildSpringApiInventory } from '../benchmark/spring-api-test-inventory.mjs';

const files = [
  'com/yrootlab/onmaru/HealthEndpointTests.java',
  'com/yrootlab/onmaru/journey/JourneyContractE2ETests.java',
  'com/yrootlab/onmaru/testing/postgres/CatalogMigrationTests.java',
  'com/yrootlab/onmaru/testing/postgres/JdbcCatalogPublicPlaceIdStoreTests.java',
  'com/yrootlab/onmaru/testing/postgres/AudioMigrationTests.java',
  'com/yrootlab/onmaru/testing/postgres/JdbcAudioRevisionStoreTests.java',
  'com/yrootlab/onmaru/testing/postgres/JdbcVisitReviewStoreTests.java',
  'com/yrootlab/onmaru/tourism/audio/JdbcOdiiStoryReadStoreIntegrationTests.java',
];

test('classifies every Spring API test exactly once into the four required shards', () => {
  const inventory = buildSpringApiInventory(files);

  assert.deepEqual(Object.keys(inventory.shards), [
    'unit-contract',
    'postgres-catalog',
    'postgres-audio',
    'postgres-other',
  ]);
  assert.equal(inventory.unclassified.length, 0);
  assert.equal(inventory.totalTests, files.length);
  assert.equal(inventory.shards['unit-contract'].tests.length, 2);
  assert.equal(inventory.shards['postgres-catalog'].tests.length, 2);
  assert.equal(inventory.shards['postgres-audio'].tests.length, 3);
  assert.equal(inventory.shards['postgres-other'].tests.length, 1);

  const allTests = Object.values(inventory.shards).flatMap((shard) => shard.tests);
  assert.equal(new Set(allTests).size, files.length);
});

test('fails closed for an unknown test classification', () => {
  const inventory = buildSpringApiInventory(['com/yrootlab/onmaru/unknown/MysteryTests.java']);
  assert.deepEqual(inventory.unclassified, ['com.yrootlab.onmaru.unknown.MysteryTests']);
  assert.equal(inventory.totalTests, 0);
});
