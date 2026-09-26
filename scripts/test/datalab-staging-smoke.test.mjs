import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

import {
  assertRevisionPreserved,
  sanitize,
  validateConfig,
  validateDatabaseEvidence,
  validatePublicResponses,
} from '../datalab-staging-smoke.mjs';

describe('DataLab staging smoke contract', () => {
  it('fails closed while naming only missing environment keys', () => {
    assert.throws(
      () => validateConfig({ STAGING_SPRING_URL: 'https://staging.example' }),
      /ONMARU_DATALAB_OPERATIONS_TOKEN, ONMARU_DATALAB_VISITOR_SERVICE_KEY, ONMARU_STAGING_READONLY_DB_URL/,
    );
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
    }));
    assert.throws(() => validateDatabaseEvidence({
      registryTotal: 4,
      activeCount: 4,
      officialActiveCount: 4,
      activeRevisionId: 'revision-1',
      completeObservationCount: 0,
    }), /COMPLETE observation/);
  });

  it('detects an active revision change during failure-preservation verification', () => {
    assert.doesNotThrow(() => assertRevisionPreserved('revision-1', 'revision-1'));
    assert.throws(() => assertRevisionPreserved('revision-1', 'revision-2'), /active revision changed/);
  });

  it('validates public observations and nullable VisitReview visitorCount', () => {
    assert.doesNotThrow(() => validatePublicResponses(
      { items: [{ metric: 'VISITOR_COUNT', coverageStatus: 'COMPLETE', value: 100 }] },
      { items: [{ visitorCount: 100 }, { visitorCount: null }] },
    ));
    assert.throws(() => validatePublicResponses(
      { items: [] },
      { items: [] },
    ), /VISITOR_COUNT observation/);
    assert.throws(() => validatePublicResponses(
      { items: [{ metric: 'VISITOR_COUNT', coverageStatus: 'COMPLETE', value: 100 }] },
      { items: [{ visitorCount: '100' }] },
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
