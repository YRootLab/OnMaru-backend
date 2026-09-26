#!/usr/bin/env node

import { readFile, writeFile } from 'node:fs/promises';

const SHA = /^[a-f0-9]{40}$/;
const ACTION_RUN_URL = /^https:\/\/github\.com\/YRootLab\/OnMaru-backend\/actions\/runs\/\d+$/;

function fail(message) {
  throw new Error(`ci run collector ${message}`);
}

function requireText(value, field) {
  if (typeof value !== 'string' || !value) fail(`${field} is required`);
  return value;
}

function durationMillis(step) {
  const started = Date.parse(step.started_at);
  const completed = Date.parse(step.completed_at);
  if (!Number.isFinite(started) || !Number.isFinite(completed) || completed < started) fail(`step ${requireText(step.name, 'name')} has invalid timestamps`);
  return completed - started;
}

export function normalizeCiRun({ run, jobs, identity, commands }) {
  if (!run || run.conclusion !== 'success') fail(`run ${run?.id ?? 'unknown'} must succeed`);
  if (!SHA.test(run.head_sha ?? '')) fail('run.head_sha must be a 40-character lowercase SHA');
  if (!ACTION_RUN_URL.test(run.html_url ?? '')) fail('run.html_url must be an immutable OnMaruBE Actions URL');
  if (!Array.isArray(commands) || commands.length === 0) fail('commands are required');
  if (!identity || typeof identity !== 'object') fail('identity is required');
  for (const field of ['runnerImage', 'dependencyMode', 'cacheState', 'javaVersion', 'pythonVersion']) requireText(identity[field], `identity.${field}`);
  const completedJobs = (jobs?.jobs ?? jobs ?? []).filter((job) => job.conclusion === 'success');
  const verify = completedJobs.find((job) => job.name === 'verify') ?? completedJobs[0];
  if (!verify || !Array.isArray(verify.steps) || verify.steps.length === 0) fail('successful job steps are required');
  const steps = verify.steps
    .filter((step) => step.conclusion === 'success' && step.name !== 'Set up job' && !step.name.startsWith('Post '))
    .map((step) => ({ name: requireText(step.name, 'step.name'), durationMillis: durationMillis(step), cpuMillis: null, maxRssBytes: null }));
  if (steps.length === 0) fail('successful timed steps are required');
  return {
    runId: String(run.id), artifactUrl: run.html_url, status: 'success',
    durationMillis: durationMillis(verify),
    identity: { commitSha: run.head_sha, ...identity }, commands, steps,
    resourceEvidence: 'unavailable-from-actions-api',
  };
}

function option(args, name) {
  const index = args.indexOf(name);
  return index < 0 ? undefined : args[index + 1];
}

async function main() {
  const args = process.argv.slice(2);
  const runPath = option(args, '--run');
  const jobsPath = option(args, '--jobs');
  const identityPath = option(args, '--identity');
  const commandsPath = option(args, '--commands');
  const output = option(args, '--output');
  if (!runPath || !jobsPath || !identityPath || !commandsPath || !output) fail('usage: ci-run-collector.mjs --run <run.json> --jobs <jobs.json> --identity <identity.json> --commands <commands.json> --output <evidence.json>');
  const [run, jobs, identity, commands] = await Promise.all([runPath, jobsPath, identityPath, commandsPath].map(async (path) => JSON.parse(await readFile(path, 'utf8'))));
  await writeFile(output, `${JSON.stringify(normalizeCiRun({ run, jobs, identity, commands }), null, 2)}\n`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((error) => { console.error(error.message); process.exitCode = 1; });
}
