#!/usr/bin/env node

import { readFile, writeFile } from 'node:fs/promises';

const SHA = /^[a-f0-9]{40}$/;
const ACTION_RUN_URL = /^https:\/\/github\.com\/YRootLab\/OnMaru-backend\/actions\/runs\/\d+$/;
const IDENTITY_FIELDS = ['commitSha', 'runnerImage', 'dependencyMode', 'cacheState', 'javaVersion', 'pythonVersion'];

function fail(message) {
  throw new Error(`serial baseline ${message}`);
}

function median(values) {
  const sorted = [...values].sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
}

function validateIdentity(identity) {
  if (!identity || typeof identity !== 'object') fail('identity is required');
  if (!SHA.test(identity.commitSha ?? '')) fail('identity.commitSha must be a 40-character lowercase SHA');
  for (const field of IDENTITY_FIELDS.slice(1)) {
    if (typeof identity[field] !== 'string' || !identity[field]) fail(`identity.${field} is required`);
  }
}

function validateRun(run) {
  if (!run || typeof run !== 'object') fail('run must be an object');
  if (!/^\d+$/.test(String(run.runId ?? ''))) fail('runId is required');
  if (!ACTION_RUN_URL.test(run.artifactUrl ?? '')) fail('artifactUrl must be an immutable OnMaruBE Actions run URL');
  if (run.status !== 'success') fail(`run ${run.runId} must succeed`);
  if (!Number.isFinite(run.durationMillis) || run.durationMillis < 0) fail(`run ${run.runId} has invalid wall-clock duration`);
  validateIdentity(run.identity);
  if (!Array.isArray(run.commands) || run.commands.length === 0 || run.commands.some((command) => typeof command !== 'string' || !command)) fail(`run ${run.runId} commands are required`);
  if (!Array.isArray(run.steps) || run.steps.length === 0) fail(`run ${run.runId} steps are required`);
  const resourceEvidence = run.resourceEvidence ?? 'available';
  if (!['available', 'unavailable-from-actions-api'].includes(resourceEvidence)) fail(`run ${run.runId} has invalid resource evidence`);
  for (const step of run.steps) {
    if (!step?.name || !Number.isFinite(step.durationMillis) || step.durationMillis < 0) fail(`run ${run.runId} has invalid step duration`);
    const hasResourceMetrics = Number.isFinite(step.cpuMillis) && step.cpuMillis >= 0 && Number.isFinite(step.maxRssBytes) && step.maxRssBytes >= 0;
    const lacksResourceMetrics = step.cpuMillis === null && step.maxRssBytes === null;
    if (!hasResourceMetrics && !lacksResourceMetrics) fail(`run ${run.runId} has invalid resource evidence`);
    if (resourceEvidence === 'available' && !hasResourceMetrics) fail(`run ${run.runId} has unavailable resource evidence`);
    if (resourceEvidence === 'unavailable-from-actions-api' && !lacksResourceMetrics) fail(`run ${run.runId} has inconsistent resource evidence`);
  }
}

function compareIdentity(expected, candidate) {
  return IDENTITY_FIELDS.find((field) => expected[field] !== candidate[field]);
}

export function createSerialBaseline({ suite, runs }) {
  if (typeof suite !== 'string' || !suite) fail('suite is required');
  if (!Array.isArray(runs) || runs.length !== 3) fail('requires exactly three runs');
  runs.forEach(validateRun);
  const [first] = runs;
  for (const run of runs.slice(1)) {
    const mismatch = compareIdentity(first.identity, run.identity);
    if (mismatch) fail(`runs are not comparable: ${mismatch}`);
    if ((run.resourceEvidence ?? 'available') !== (first.resourceEvidence ?? 'available')) fail('runs are not comparable: resourceEvidence');
  }
  const resourceEvidence = first.resourceEvidence ?? 'available';
  const runDurations = runs.map((run) => run.durationMillis);
  return {
    schemaVersion: 1,
    status: 'valid',
    suite,
    runCount: runs.length,
    identity: first.identity,
    artifactUrls: runs.map((run) => run.artifactUrl),
    metrics: {
      verifyWallClockMedianMillis: median(runDurations),
      verifyWorkMedianMillis: resourceEvidence === 'available' ? median(runs.map((run) => run.steps.reduce((total, step) => total + step.cpuMillis, 0))) : null,
      peakRssBytes: resourceEvidence === 'available' ? Math.max(...runs.flatMap((run) => run.steps.map((step) => step.maxRssBytes))) : null,
    },
    resourceEvidence,
    runs: runs.map(({ runId, artifactUrl, durationMillis, commands, steps }) => ({ runId: String(runId), artifactUrl, durationMillis, commands, steps })),
  };
}

function option(args, name) {
  const index = args.indexOf(name);
  return index < 0 ? undefined : args[index + 1];
}

async function main() {
  const args = process.argv.slice(2);
  const input = option(args, '--input');
  const output = option(args, '--output');
  if (!input || !output) fail('usage: serial-baseline.mjs --input <runs.json> --output <baseline.json>');
  const request = JSON.parse(await readFile(input, 'utf8'));
  const baseline = createSerialBaseline(request);
  await writeFile(output, `${JSON.stringify(baseline, null, 2)}\n`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
