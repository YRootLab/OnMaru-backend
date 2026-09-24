import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  createBenchmarkManifest,
  createReleaseMetadata,
  hashConfig,
  redactSensitive,
  validateReleaseMetadata,
} from '../benchmark/release-metadata.mjs';

const sha = 'a'.repeat(40);
const digest = 'sha256:' + 'b'.repeat(64);
const deployedDigest = 'sha256:' + 'c'.repeat(64);

function validReleaseInput(overrides = {}) {
  return {
    releaseId: 'v0.4.0',
    tag: 'v0.4.0',
    commitSha: sha,
    environment: 'staging',
    workflowRunId: '123456',
    services: [
      {
        name: 'spring-api',
        image: 'ghcr.io/yrootlab/onmaru-backend/spring-api',
        imageTag: sha,
        expectedDigest: digest,
        deployedDigest: digest,
        readinessPath: '/actuator/health',
      },
      {
        name: 'ai-service',
        image: 'ghcr.io/yrootlab/onmaru-backend/ai-service',
        imageTag: sha,
        expectedDigest: digest,
        deployedDigest: digest,
        readinessPath: '/ready',
      },
    ],
    ...overrides,
  };
}

test('creates release metadata with validated release identity', () => {
  const metadata = createReleaseMetadata(validReleaseInput());

  assert.equal(metadata.schemaVersion, 1);
  assert.equal(metadata.releaseId, 'v0.4.0');
  assert.equal(metadata.services[0].expectedDigest, digest);
  assert.deepEqual(validateReleaseMetadata(metadata), metadata);
});

test('rejects malformed tag, SHA, digest, and non-staging environment', () => {
  assert.throws(() => createReleaseMetadata(validReleaseInput({ tag: '0.4.0' })), /tag/);
  assert.throws(() => createReleaseMetadata(validReleaseInput({ commitSha: sha.toUpperCase() })), /commitSha/);
  assert.throws(() => createReleaseMetadata(validReleaseInput({
    services: [{ ...validReleaseInput().services[0], expectedDigest: 'sha256:not-a-digest' }],
  })), /expectedDigest/);
  assert.throws(() => createReleaseMetadata(validReleaseInput({ environment: 'production' })), /environment/);
});

test('fails closed when expected and deployed image digests differ', () => {
  assert.throws(
    () => createReleaseMetadata(validReleaseInput({
      services: [{ ...validReleaseInput().services[0], deployedDigest }],
    })),
    /deployedDigest mismatch.*spring-api/,
  );
});

test('hashes equivalent config bytes deterministically', () => {
  assert.equal(hashConfig('{"warmupRuns":2}'), hashConfig('{"warmupRuns":2}'));
  assert.match(hashConfig('{"warmupRuns":2}'), /^[a-f0-9]{64}$/);
});

test('creates evidence manifest with unverified toolkit and config hash', () => {
  const manifest = createBenchmarkManifest({
    benchmarkRunId: 'run-20260923-001',
    releaseId: 'v0.4.0',
    baselineReleaseId: 'v0.3.2',
    candidateReleaseId: 'v0.4.0',
    commitSha: sha,
    imageDigests: { 'spring-api': digest, 'ai-service': digest },
    environment: 'staging',
    suite: 'onmaru-release-smoke',
    toolkit: { name: 'pipeline-toolkit', version: 'unverified' },
    config: '{"warmupRuns":2,"measuredRuns":3}',
    status: 'inconclusive',
    rawUri: 'benchmark/raw/run-20260923-001.json',
    normalizedUri: 'benchmark/normalized/run-20260923-001.json',
    reportUri: 'benchmark/reports/run-20260923-001.json',
  });

  assert.equal(manifest.configSha256, hashConfig('{"warmupRuns":2,"measuredRuns":3}'));
  assert.equal(manifest.toolkit.version, 'unverified');
  assert.equal(manifest.status, 'inconclusive');
});

test('redacts secrets and PII from diagnostic data', () => {
  const safe = redactSensitive({
    token: 'super-secret-token',
    ownerEmail: 'person@example.com',
    nested: { password: 'hunter2' },
    message: 'contact person@example.com with token=abc',
  });

  assert.deepEqual(safe, {
    token: '[REDACTED]',
    ownerEmail: '[REDACTED]',
    nested: { password: '[REDACTED]' },
    message: 'contact [REDACTED] with token=[REDACTED]',
  });
  assert.throws(() => createReleaseMetadata(validReleaseInput({ workflowRunId: 'person@example.com' })), /sensitive|PII/);
});

test('rejects credential-bearing URLs and path traversal in manifest', () => {
  assert.throws(() => createBenchmarkManifest({
    benchmarkRunId: 'run-1', releaseId: 'v0.4.0', baselineReleaseId: 'v0.3.2', candidateReleaseId: 'v0.4.0',
    commitSha: sha, imageDigests: { 'spring-api': digest, 'ai-service': digest }, environment: 'staging',
    suite: 'onmaru-release-smoke', toolkit: { name: 'pipeline-toolkit', version: 'unverified' },
    configSha256: 'a'.repeat(64), status: 'inconclusive', rawUri: 'https://user:pass@example.com/raw.json',
    normalizedUri: 'benchmark/normalized/run-1.json', reportUri: 'benchmark/reports/run-1.json',
  }), /sensitive|credential/);
  assert.throws(() => createBenchmarkManifest({
    benchmarkRunId: 'run-1', releaseId: 'v0.4.0', baselineReleaseId: 'v0.3.2', candidateReleaseId: 'v0.4.0',
    commitSha: sha, imageDigests: { 'spring-api': digest, 'ai-service': digest }, environment: 'staging',
    suite: 'onmaru-release-smoke', toolkit: { name: 'pipeline-toolkit', version: 'unverified' },
    configSha256: 'a'.repeat(64), status: 'inconclusive', rawUri: '../raw.json',
    normalizedUri: 'benchmark/normalized/run-1.json', reportUri: 'benchmark/reports/run-1.json',
  }), /path/);
});

test('fails closed when config content contains a secret before hashing', () => {
  assert.throws(() => createBenchmarkManifest({
    benchmarkRunId: 'run-1', releaseId: 'v0.4.0', baselineReleaseId: 'v0.3.2', candidateReleaseId: 'v0.4.0',
    commitSha: sha, imageDigests: { 'spring-api': digest, 'ai-service': digest }, environment: 'staging',
    suite: 'onmaru-release-smoke', toolkit: { name: 'pipeline-toolkit', version: 'unverified' },
    config: { timeoutMs: 1000, serviceToken: 'must-not-be-hashed' }, status: 'inconclusive',
    rawUri: 'benchmark/raw/run-1.json', normalizedUri: 'benchmark/normalized/run-1.json', reportUri: 'benchmark/reports/run-1.json',
  }), /sensitive|PII/);
});
