import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import {
  sanitize,
  validateConfig,
  validateDatabaseEvidence,
  validateDeploymentIdentity,
  validatePublicResponses,
} from '../datalab-staging-smoke.mjs';

describe('DataLab staging smoke contract', () => {
  it('binds workflow checkout, deployed identity, and preservation evidence to one SHA', () => {
    const workflow = readFileSync(fileURLToPath(new URL(
      '../../.github/workflows/datalab-staging-smoke.yml', import.meta.url,
    )), 'utf8');

    assert.match(workflow, /ref:.*workflow_run\.head_sha/);
    assert.match(workflow, /EXPECTED_DEPLOYED_SHA:.*workflow_run\.head_sha/);
    assert.match(workflow, /keepsThePreviousActiveRevisionWhenStagingFails/);
    assert.match(workflow, /PRESERVATION_OUTCOME.*steps\.preservation\.outcome/);
  });

  it('fails closed while naming only missing environment keys', () => {
    assert.throws(
      () => validateConfig({ STAGING_SPRING_URL: 'https://staging.example' }),
      /ONMARU_DATALAB_OPERATIONS_TOKEN/,
    );
  });

  it('binds smoke evidence to the deployed workflow SHA and verified failure test', () => {
    assert.doesNotThrow(() => validateDeploymentIdentity(
      { buildGitSha: 'a'.repeat(40) },
      'a'.repeat(40),
    ));
    assert.throws(() => validateDeploymentIdentity(
      { buildGitSha: 'b'.repeat(40) },
      'a'.repeat(40),
    ), /deployed Spring SHA/);
  });

  it('requires an active official mapping, active revision, and complete observation', () => {
    assert.doesNotThrow(() => validateDatabaseEvidence({
      registryTotal: 4,
      activeCount: 4,
      pendingCount: 0,
      rejectedCount: 0,
      officialActiveCount: 4,
      activeRevisionId: 'revision-1',
      completeObservationCount: 4,
      notAvailableObservationCount: 0,
      smokeRegionCode: 'kr-45-jeonju',
      smokeBasisDate: '2026-09-26',
      smokeVisitorCount: 100,
    }));
    assert.throws(() => validateDatabaseEvidence({
      registryTotal: 4,
      activeCount: 4,
      officialActiveCount: 4,
      activeRevisionId: 'revision-1',
      completeObservationCount: 0,
    }), /COMPLETE observation/);
  });

  it('validates public observations and nullable VisitReview visitorCount', () => {
    assert.doesNotThrow(() => validatePublicResponses(
      { items: [{ region: { regionCode: 'kr-45-jeonju' }, observedDate: '2026-09-26', metric: 'VISITOR_COUNT', coverageStatus: 'COMPLETE', value: 100 }] },
      { items: [{ visitorCount: 100 }] },
      { smokeRegionCode: 'kr-45-jeonju', smokeBasisDate: '2026-09-26', smokeVisitorCount: 100 },
    ));
    assert.throws(() => validatePublicResponses(
      { items: [] },
      { items: [] },
      { smokeRegionCode: 'kr-45-jeonju', smokeBasisDate: '2026-09-26', smokeVisitorCount: 100 },
    ), /VISITOR_COUNT observation/);
    assert.throws(() => validatePublicResponses(
      { items: [{ region: { regionCode: 'kr-45-jeonju' }, observedDate: '2026-09-26', metric: 'VISITOR_COUNT', coverageStatus: 'COMPLETE', value: 100 }] },
      { items: [{ visitorCount: 99 }] },
      { smokeRegionCode: 'kr-45-jeonju', smokeBasisDate: '2026-09-26', smokeVisitorCount: 100 },
    ), /visitorCount/);
  });

  it('recursively removes sensitive and raw-provider fields from evidence', () => {
    assert.deepEqual(sanitize({
      revisionId: 'revision-1',
      token: 'hidden',
      nested: {
        password: 'hidden',
        sourceUrl: 'https://provider.example',
        payload: { raw: true },
        activeCount: 4,
      },
    }), {
      revisionId: 'revision-1',
      nested: { activeCount: 4 },
    });
  });
});
