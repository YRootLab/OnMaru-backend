#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const REQUIRED_ENV = [
  'STAGING_SPRING_URL',
  'ONMARU_DATALAB_OPERATIONS_TOKEN',
  'ONMARU_DATALAB_VISITOR_SERVICE_KEY',
  'ONMARU_STAGING_READONLY_DB_URL',
  'EXPECTED_DEPLOYED_SHA',
  'DATALAB_FAILED_BATCH_TEST_VERIFIED',
];

const DATABASE_EVIDENCE_SQL = String.raw`
WITH current_registry AS (
    SELECT mapping.*
    FROM onmaru.catalog_datalab_region_mappings mapping
    JOIN onmaru.catalog_region_source_codes source
      ON source.provider = mapping.provider
     AND source.dataset = mapping.dataset
     AND source.source_code = mapping.source_code
     AND source.valid_from = mapping.valid_from
     AND source.region_id = mapping.region_id
    JOIN onmaru.catalog_regions region ON region.id = mapping.region_id
    WHERE region.active
      AND source.valid_from <= CURRENT_DATE
      AND (source.valid_to IS NULL OR source.valid_to >= CURRENT_DATE)
), active_revision AS (
    SELECT active.revision_id
    FROM onmaru.catalog_active_datasets active
    WHERE active.dataset = 'kto-datalab-visitor'
), active_observations AS (
    SELECT observation.*, region.code AS region_code
    FROM active_revision active
    JOIN onmaru.insights_visitor_observations observation
      ON observation.revision_id = active.revision_id
    JOIN onmaru.catalog_regions region ON region.id = observation.region_id
), smoke_region AS (
    SELECT region_code, basis_date, visitor_count
    FROM active_observations
    WHERE coverage_status = 'COMPLETE'
      AND visitor_count IS NOT NULL
    ORDER BY basis_date DESC, region_code
    LIMIT 1
)
SELECT jsonb_build_object(
    'registryTotal', (SELECT count(*) FROM current_registry),
    'activeCount', (SELECT count(*) FROM current_registry WHERE status = 'ACTIVE'),
    'pendingCount', (SELECT count(*) FROM current_registry WHERE status = 'PENDING'),
    'rejectedCount', (SELECT count(*) FROM current_registry WHERE status = 'REJECTED'),
    'officialActiveCount', (
        SELECT count(*) FROM current_registry
        WHERE status = 'ACTIVE'
          AND source_url ~ '^https://'
          AND source_observed_at IS NOT NULL
          AND verified_by IS NOT NULL
          AND verified_at IS NOT NULL
    ),
    'activeRevisionId', (SELECT revision_id::text FROM active_revision),
    'completeObservationCount', (
        SELECT count(*) FROM active_observations
        WHERE coverage_status = 'COMPLETE' AND visitor_count IS NOT NULL
    ),
    'notAvailableObservationCount', (
        SELECT count(*) FROM active_observations
        WHERE coverage_status = 'NOT_AVAILABLE' AND visitor_count IS NULL
    ),
    'smokeRegionCode', (SELECT region_code FROM smoke_region),
    'smokeBasisDate', (SELECT basis_date::text FROM smoke_region),
    'smokeVisitorCount', (SELECT visitor_count FROM smoke_region)
)::text;
`;

export function validateConfig(environment) {
  const missing = REQUIRED_ENV.filter((name) => !environment[name]?.trim());
  if (missing.length > 0) {
    throw new Error(`missing required staging configuration: ${missing.join(', ')}`);
  }
  if (!/^[0-9a-f]{40}$/.test(environment.EXPECTED_DEPLOYED_SHA)) {
    throw new Error('EXPECTED_DEPLOYED_SHA must be a full lowercase Git SHA');
  }
  if (environment.DATALAB_FAILED_BATCH_TEST_VERIFIED !== 'true') {
    throw new Error('failed-batch preservation integration test was not verified');
  }
  return {
    springUrl: environment.STAGING_SPRING_URL.replace(/\/+$/, ''),
    operationsToken: environment.ONMARU_DATALAB_OPERATIONS_TOKEN,
    databaseUrl: environment.ONMARU_STAGING_READONLY_DB_URL,
    expectedDeployedSha: environment.EXPECTED_DEPLOYED_SHA,
  };
}

export function validateDatabaseEvidence(evidence) {
  const activeCount = Number(evidence?.activeCount ?? 0);
  const officialActiveCount = Number(evidence?.officialActiveCount ?? 0);
  if (activeCount < 1) {
    throw new Error('DataLab registry has no ACTIVE mapping');
  }
  if (officialActiveCount !== activeCount) {
    throw new Error('an ACTIVE mapping is missing official provenance');
  }
  if (!evidence?.activeRevisionId) {
    throw new Error('kto-datalab-visitor active revision is missing');
  }
  if (Number(evidence?.completeObservationCount ?? 0) < 1) {
    throw new Error('active revision has no COMPLETE observation');
  }
  if (!evidence?.smokeRegionCode) {
    throw new Error('no region is available for public API smoke');
  }
  if (!evidence?.smokeBasisDate
      || !Number.isInteger(Number(evidence?.smokeVisitorCount))
      || Number(evidence.smokeVisitorCount) < 0) {
    throw new Error('smoke observation identity is incomplete');
  }
}

export function validateDeploymentIdentity(sync, expectedSha) {
  if (sync?.buildGitSha !== expectedSha) {
    throw new Error('deployed Spring SHA does not match the staging deployment SHA');
  }
}

export function validatePublicResponses(insights, reviews, expected) {
  const observations = Array.isArray(insights?.items) ? insights.items : [];
  if (!observations.some((item) => item?.metric === 'VISITOR_COUNT'
      && item?.coverageStatus === 'COMPLETE'
      && item?.region?.regionCode === expected.smokeRegionCode
      && item?.observedDate === expected.smokeBasisDate
      && item?.value === Number(expected.smokeVisitorCount))) {
    throw new Error('public Insights response has no matching COMPLETE VISITOR_COUNT observation');
  }
  const reviewItems = Array.isArray(reviews?.items) ? reviews.items : [];
  if (reviewItems.length === 0) {
    throw new Error('public VisitReview response has no item for visitorCount projection verification');
  }
  for (const item of reviewItems) {
    if (item?.visitorCount !== Number(expected.smokeVisitorCount)) {
      throw new Error('VisitReview visitorCount does not match the active DataLab observation');
    }
  }
}

export function sanitize(value) {
  if (Array.isArray(value)) {
    return value.map(sanitize);
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value)
      .filter(([key]) => !/(token|secret|password|sourceurl|payload|regioncode|servicekey|databaseurl)/i.test(key))
      .map(([key, nested]) => [key, sanitize(nested)]));
  }
  return value;
}

function queryDatabase(databaseUrl) {
  const result = spawnSync('psql', [
    '--no-align',
    '--tuples-only',
    '--set', 'ON_ERROR_STOP=1',
    '--command', DATABASE_EVIDENCE_SQL,
  ], {
    encoding: 'utf8',
    env: { ...process.env, PGDATABASE: databaseUrl },
  });
  if (result.status !== 0) {
    throw new Error('read-only DataLab evidence query failed');
  }
  try {
    return JSON.parse(result.stdout.trim());
  } catch {
    throw new Error('read-only DataLab evidence query returned invalid JSON');
  }
}

async function fetchJson(url, options = {}) {
  const response = await fetch(url, { ...options, signal: AbortSignal.timeout(30_000) });
  if (!response.ok) {
    throw new Error(`staging API returned HTTP ${response.status}`);
  }
  return response.json();
}

function writeEvidence(path, evidence) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, `${JSON.stringify(sanitize(evidence), null, 2)}\n`, { mode: 0o600 });
}

async function main(environment = process.env) {
  const evidencePath = environment.DATALAB_SMOKE_EVIDENCE_PATH
    || '/tmp/datalab-staging-smoke.json';
  try {
    const config = validateConfig(environment);
    const sync = await fetchJson(`${config.springUrl}/api/v1/operations/datalab/visitor-sync`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${config.operationsToken}` },
    });
    validateDeploymentIdentity(sync, config.expectedDeployedSha);
    if (sync.published !== true || Number(sync.observationCount ?? 0) < 1
        || Number(sync.quarantinedCount ?? 0) !== 0) {
      throw new Error('staging DataLab sync did not publish a clean observation batch');
    }

    const database = queryDatabase(config.databaseUrl);
    validateDatabaseEvidence(database);
    const encodedRegion = encodeURIComponent(database.smokeRegionCode);
    const insights = await fetchJson(
      `${config.springUrl}/api/v1/insights/observations?regionCode=${encodedRegion}&metric=VISITOR_COUNT`,
    );
    const reviews = await fetchJson(
      `${config.springUrl}/api/v1/visit-reviews?scope=REGION&regionCode=${encodedRegion}&limit=20`,
    );
    validatePublicResponses(insights, reviews, database);

    const evidence = {
      schemaVersion: '1.0',
      status: 'passed',
      observedAt: new Date().toISOString(),
      gitSha: config.expectedDeployedSha,
      workflowRunId: environment.GITHUB_RUN_ID ?? null,
      sync,
      database,
      publicApi: {
        insightsObservationCount: insights.items.length,
        visitReviewCount: reviews.items.length,
        visitorCountContract: 'non-negative integer or null',
      },
      failedBatchPreservation: {
        verified: true,
        verification: 'JdbcDataLabVisitorSnapshotPublisherTests.keepsThePreviousActiveRevisionWhenStagingFails',
        recovery: 'Keep the current active revision, fix registry/provider configuration, and rerun this workflow.',
      },
    };
    writeEvidence(evidencePath, evidence);
    process.stdout.write(`DataLab staging smoke passed; sanitized evidence: ${evidencePath}\n`);
  } catch (error) {
    writeEvidence(evidencePath, {
      schemaVersion: '1.0',
      status: 'failed',
      observedAt: new Date().toISOString(),
      gitSha: environment.EXPECTED_DEPLOYED_SHA ?? null,
      workflowRunId: environment.GITHUB_RUN_ID ?? null,
      error: { message: error instanceof Error ? error.message : 'unknown smoke failure' },
    });
    throw error;
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main().catch((error) => {
    process.stderr.write(`DataLab staging smoke failed: ${error.message}\n`);
    process.exitCode = 1;
  });
}
