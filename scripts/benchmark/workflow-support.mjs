#!/usr/bin/env node

import { readFile, writeFile } from 'node:fs/promises';
import { createReleaseMetadata, createBenchmarkManifest, hashConfig } from './release-metadata.mjs';
import { readConfig, runComparison } from './toolkit-adapter.mjs';

const RELEASE_TAG = /^v\d+\.\d+\.\d+$/;
const DIGEST = /^sha256:[a-f0-9]{64}$/;

export function resolveReleaseTag({ tag, refName, eventName }) {
  const value = tag || refName?.replace(/^refs\/tags\//, '');
  if (!value || !RELEASE_TAG.test(value)) {
    throw new Error(`release tag must match vX.Y.Z (event=${eventName ?? 'unknown'})`);
  }
  return value;
}

export function validateDigestPair(expected, deployed, service) {
  if (!DIGEST.test(expected) || !DIGEST.test(deployed)) {
    throw new Error(`invalid ${service} image digest`);
  }
  if (expected !== deployed) {
    throw new Error(`deployed digest mismatch for ${service}: expected ${expected}, got ${deployed}`);
  }
  return true;
}

export function createReleaseEvidence({ tag, commitSha, workflowRunId, expectedDigests, deployedDigests }) {
  const services = [
    { name: 'spring-api', image: 'ghcr.io/yrootlab/onmaru-backend/spring-api', readinessPath: '/actuator/health' },
    { name: 'ai-service', image: 'ghcr.io/yrootlab/onmaru-backend/ai-service', readinessPath: '/ready' },
  ].map((service) => {
    const expectedDigest = expectedDigests[service.name];
    const deployedDigest = deployedDigests[service.name];
    validateDigestPair(expectedDigest, deployedDigest, service.name);
    return { ...service, imageTag: commitSha, expectedDigest, deployedDigest };
  });
  return createReleaseMetadata({
    releaseId: tag,
    tag,
    commitSha,
    environment: 'staging',
    workflowRunId: String(workflowRunId),
    services,
  });
}

export function selectPreviousSuccessfulRelease(releases = [], candidateTag) {
  return releases
    .filter((release) => release.tagName && release.tagName !== candidateTag)
    .filter((release) => release.isDraft !== true && release.isPrerelease !== true)
    .filter((release) => release.benchmarkStatus === 'improved' || release.benchmarkStatus === 'unchanged')
    .sort((left, right) => new Date(right.publishedAt || 0) - new Date(left.publishedAt || 0))[0] || null;
}

function releaseStatus(release) {
  if (release.benchmarkStatus) return release.benchmarkStatus;
  const match = String(release.body || '').match(/benchmark(?:-|\s)status\s*:\s*(improved|unchanged)/i);
  return match?.[1]?.toLowerCase() || null;
}

export function lookupBaseline(releases = [], candidateTag, requestedTag) {
  const candidates = releases.map((release) => ({
    ...release,
    tagName: release.tagName || release.tag_name,
    publishedAt: release.publishedAt || release.published_at,
    isDraft: release.isDraft ?? release.draft,
    isPrerelease: release.isPrerelease ?? release.prerelease,
    benchmarkStatus: releaseStatus(release),
  }));
  const requested = requestedTag && candidates.find((release) => release.tagName === requestedTag);
  const selected = requested && (requested.benchmarkStatus === 'improved' || requested.benchmarkStatus === 'unchanged')
    ? requested
    : selectPreviousSuccessfulRelease(candidates, candidateTag);
  if (!selected) return {};
  return {
    releaseId: selected.tagName,
    tag: selected.tagName,
    success: true,
    source: requested ? 'requested' : 'target-branch-recent',
    compatibility: selected.compatibility || {},
  };
}

export function promotionDecision(status) {
  if (status === 'improved' || status === 'unchanged') {
    return { status, outcome: 'pass', autoPromotion: true, approvalRequired: false, warning: null };
  }
  if (status === 'regressed') {
    return { status, outcome: 'warning', autoPromotion: false, approvalRequired: true, warning: 'Regression detected; explicit approval is required.' };
  }
  return { status: status || 'inconclusive', outcome: 'blocked', autoPromotion: false, approvalRequired: true, warning: 'Inconclusive benchmark; automatic promotion is blocked.' };
}

export function renderJobSummary({ release, baseline, comparison, decision }) {
  const lines = [
    '## Release benchmark',
    '',
    `- Candidate: \`${release?.releaseId ?? 'unknown'}\``,
    `- Baseline: \`${baseline?.releaseId ?? 'not found'}\``,
    `- Status: **${comparison?.status ?? 'inconclusive'}**`,
    `- Gate: **${decision.outcome}**`,
    `- Run ID: \`${comparison?.benchmarkRunId ?? comparison?.runId ?? 'not available'}\``,
  ];
  for (const [name, metric] of Object.entries(comparison?.metrics ?? {})) {
    lines.push(`- ${name}: baseline median/p95=${metric.baseline?.median ?? 'n/a'}/${metric.baseline?.p95 ?? 'n/a'}, candidate median/p95=${metric.candidate?.median ?? 'n/a'}/${metric.candidate?.p95 ?? 'n/a'}, delta=${metric.absoluteDelta ?? 'n/a'} (${metric.relativeDelta ?? 'n/a'})`);
  }
  if (comparison?.reasons?.length) lines.push(`- Reasons: ${comparison.reasons.join(', ')}`);
  if (decision.warning) lines.push(`- Warning: ${decision.warning}`);
  return `${lines.join('\n')}\n`;
}

export async function compareRelease({ configPath, candidatePath, baselinePath, outputPath }) {
  const config = await readConfig(configPath);
  const candidate = JSON.parse(await readFile(candidatePath, 'utf8'));
  const baseline = baselinePath ? JSON.parse(await readFile(baselinePath, 'utf8')) : null;
  const comparison = await runComparison({ config, baseline, candidate });
  await writeFile(outputPath, `${JSON.stringify(comparison, null, 2)}\n`);
  return comparison;
}

export async function createManifest({ release, baseline, comparison, config, runId }) {
  const status = comparison?.status ?? 'inconclusive';
  return createBenchmarkManifest({
    benchmarkRunId: runId,
    releaseId: release.releaseId,
    baselineReleaseId: baseline?.releaseId ?? release.releaseId,
    candidateReleaseId: release.releaseId,
    commitSha: release.commitSha,
    imageDigests: Object.fromEntries(release.services.map((service) => [service.name, service.deployedDigest])),
    environment: 'staging',
    suite: config.suite,
    toolkit: { name: 'pipeline-toolkit', version: config.toolkit?.version === undefined ? 'unverified' : config.toolkit.version },
    config,
    status,
    rawUri: `benchmark/raw/${runId}.json`,
    normalizedUri: `benchmark/normalized/${runId}.json`,
    reportUri: `benchmark/reports/${runId}.json`,
  });
}

export function createTrendManifest({ release, repository, runId, artifactUri, configHash, runnerProfile, toolkitVersion, wallClockSeconds, suite }) {
  if (!Number.isFinite(wallClockSeconds) || wallClockSeconds < 0) throw new Error('wallClockSeconds must be a non-negative finite number');
  const imageDigest = release.services[0]?.deployedDigest;
  if (!imageDigest) throw new Error('release must include a deployed image digest');
  return {
    schema_version: '1.0',
    release: { repository, tag: release.releaseId, commit_sha: release.commitSha, image_digest: imageDigest },
    run: {
      run_id: String(runId), status: 'success', environment: release.environment, suite,
      config_hash: configHash, runner_profile: runnerProfile, toolkit_version: toolkitVersion, artifact_uri: artifactUri,
    },
    metrics: [{ id: 'pipeline.wall_clock', unit: 'seconds', samples: [wallClockSeconds] }],
  };
}

function output(values) {
  const target = process.env.GITHUB_OUTPUT;
  if (target) return writeFile(target, Object.entries(values).map(([key, value]) => `${key}=${value}`).join('\n') + '\n', { flag: 'a' });
  return Promise.resolve();
}

async function main() {
  const [command, ...args] = process.argv.slice(2);
  const value = (name) => { const index = args.indexOf(name); return index >= 0 ? args[index + 1] : undefined; };
  if (command === 'metadata') {
    const release = createReleaseEvidence({
      tag: value('--tag'), commitSha: value('--sha'), workflowRunId: value('--workflow-run-id'),
      expectedDigests: { 'spring-api': value('--spring-expected'), 'ai-service': value('--ai-expected') },
      deployedDigests: { 'spring-api': value('--spring-deployed'), 'ai-service': value('--ai-deployed') },
    });
    await writeFile(value('--output'), `${JSON.stringify(release, null, 2)}\n`);
    await output({ release_id: release.releaseId });
    return;
  }
  if (command === 'compare') {
    const comparison = await compareRelease({ configPath: value('--config'), candidatePath: value('--candidate'), baselinePath: value('--baseline'), outputPath: value('--output') });
    await output({ status: comparison.status || 'inconclusive' });
    return;
  }
  if (command === 'baseline') {
    const releases = JSON.parse(await readFile(value('--input'), 'utf8'));
    const baseline = lookupBaseline(releases, value('--candidate'), value('--requested'));
    await writeFile(value('--output'), `${JSON.stringify(baseline, null, 2)}\n`);
    return;
  }
  if (command === 'manifest') {
    const release = JSON.parse(await readFile(value('--release'), 'utf8'));
    const baseline = JSON.parse(await readFile(value('--baseline'), 'utf8'));
    const comparison = JSON.parse(await readFile(value('--comparison'), 'utf8'));
    const config = await readConfig(value('--config'));
    const manifest = await createManifest({ release, baseline, comparison, config, runId: value('--run-id') });
    await writeFile(value('--output'), `${JSON.stringify(manifest, null, 2)}\n`);
    return;
  }
  if (command === 'trend-manifest') {
    const release = JSON.parse(await readFile(value('--release'), 'utf8'));
    const configPath = value('--config');
    const config = await readConfig(configPath);
    const configHash = `sha256:${hashConfig(await readFile(configPath, 'utf8'))}`;
    const manifest = createTrendManifest({
      release, repository: value('--repository'), runId: value('--run-id'), artifactUri: value('--artifact-uri'),
      configHash, runnerProfile: value('--runner-profile'), toolkitVersion: value('--toolkit-version'),
      wallClockSeconds: Number(value('--wall-clock-seconds')), suite: config.suite,
    });
    await writeFile(value('--output'), `${JSON.stringify(manifest, null, 2)}\n`);
    await output({ 'config-hash': configHash });
    return;
  }
  if (command === 'gate') {
    const comparison = JSON.parse(await readFile(value('--comparison'), 'utf8'));
    const decision = promotionDecision(comparison.status);
    await writeFile(value('--output'), `${JSON.stringify(decision, null, 2)}\n`);
    await output({ status: decision.status, outcome: decision.outcome, auto_promotion: String(decision.autoPromotion), approval_required: String(decision.approvalRequired) });
    if (process.env.GITHUB_STEP_SUMMARY) await writeFile(process.env.GITHUB_STEP_SUMMARY, renderJobSummary({ release: null, baseline: null, comparison, decision }), { flag: 'a' });
    return;
  }
  throw new Error('usage: workflow-support.mjs <metadata|compare|manifest|trend-manifest|gate> ...');
}

if (import.meta.url === `file://${process.argv[1]}`) {
  try { await main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
