#!/usr/bin/env node

import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

export async function readConfig(path) {
  const raw = await readFile(path, 'utf8');
  try {
    return JSON.parse(raw);
  } catch (error) {
    throw new Error(`configuration must be JSON-compatible YAML: ${error.message}`);
  }
}

export function selectBaseline(candidates = []) {
  const priority = [
    'production-before-candidate',
    'same-major-minor',
    'target-branch-recent',
    'policy',
  ];
  for (const source of priority) {
    const candidate = candidates.find((item) => item.source === source && item.success === true);
    if (candidate) return candidate;
  }
  return null;
}

function comparableKeys(config) {
  return {
    environment: config.environment,
    architecture: config.runner?.architecture,
    resource: config.runner?.resource,
    suite: config.suite,
    configHash: config.configHash,
    toolkitVersion: config.toolkit?.version ?? 'unverified',
    fixture: config.fixture,
    dependencyMode: config.dependencyMode,
    cache: config.cache,
  };
}

export function compatibilityReasons(config, baseline, candidate) {
  const reasons = [];
  const expected = comparableKeys(config);
  const left = { ...expected, ...(baseline?.compatibility ?? {}) };
  const right = { ...expected, ...(candidate?.compatibility ?? {}) };
  for (const key of Object.keys(expected)) {
    if (JSON.stringify(left[key]) !== JSON.stringify(right[key])) reasons.push(`${key}-mismatch`);
  }
  if (baseline?.releaseId === candidate?.releaseId) reasons.push('same-release');
  if (!baseline?.releaseId || !candidate?.releaseId) reasons.push('release-id-missing');
  return [...new Set(reasons)];
}

function metricValue(metric, side, percentile = 'p95') {
  return metric?.[side]?.[percentile] ?? metric?.[side]?.median;
}

function relativeDelta(baseline, candidate) {
  if (!Number.isFinite(baseline) || !Number.isFinite(candidate) || baseline === 0) return null;
  return (candidate - baseline) / baseline;
}

export function normalizeComparison({ config, baseline, candidate, result, failure }) {
  const reasons = [...(failure?.reasons ?? [])];
  if (config.toolkit?.provenance !== 'verified') reasons.push('toolkit-unverified');
  reasons.push(...compatibilityReasons(config, baseline, candidate));

  const metrics = result?.metrics ?? {};
  const normalizedMetrics = {};
  for (const [name, metric] of Object.entries(metrics)) {
    const base = metricValue(metric, 'baseline');
    const next = metricValue(metric, 'candidate');
    normalizedMetrics[name] = {
      ...metric,
      absoluteDelta: Number.isFinite(base) && Number.isFinite(next) ? next - base : null,
      relativeDelta: relativeDelta(base, next),
    };
  }
  const validMetric = Object.values(normalizedMetrics).some((m) => Number.isFinite(m.relativeDelta));
  if (!validMetric) reasons.push('missing-metric');
  if ((result?.runIds ?? []).length < (config.minimumValidRuns ?? 1)) reasons.push('insufficient-valid-runs');

  const uniqueReasons = [...new Set(reasons)];
  if (uniqueReasons.length > 0) {
    return { ...result, metrics: normalizedMetrics, status: 'inconclusive', reasons: uniqueReasons };
  }

  const latency = normalizedMetrics.latency_ms?.relativeDelta;
  const regression = config.policy?.latencyRegressionThreshold ?? 0.05;
  const improvement = config.policy?.improvementThreshold ?? 0.05;
  const status = latency >= regression ? 'regressed' : latency <= -improvement ? 'improved' : 'unchanged';
  return { ...result, metrics: normalizedMetrics, status, reasons: [] };
}

function expandArgs(template, inputPath, outputPath) {
  return template.map((arg) => String(arg).replaceAll('{input}', inputPath).replaceAll('{output}', outputPath));
}

export async function invokeCompare({ config, request, signal }) {
  const toolkit = config.toolkit ?? {};
  if (toolkit.provenance !== 'verified') return { result: null, failure: { reasons: ['toolkit-unverified'] } };
  if (!toolkit.executable || !Array.isArray(toolkit.commands?.compare)) {
    return { result: null, failure: { reasons: ['toolkit-unverified'] } };
  }

  const workDir = await mkdtemp(join(tmpdir(), 'onmaru-benchmark-'));
  const inputPath = join(workDir, 'input.json');
  const outputPath = join(workDir, 'output.json');
  await writeFile(inputPath, JSON.stringify(request));
  const args = expandArgs(toolkit.commands.compare, inputPath, outputPath);
  const timeoutMs = toolkit.timeoutMs ?? 30000;
  const child = spawn(toolkit.executable, args, { stdio: ['ignore', 'pipe', 'pipe'], shell: false });
  let timedOut = false;
  let stderr = '';
  child.stderr.on('data', (chunk) => { stderr += chunk.toString(); });
  const timer = setTimeout(() => { timedOut = true; child.kill('SIGTERM'); }, timeoutMs);
  const abort = () => child.kill('SIGTERM');
  signal?.addEventListener('abort', abort, { once: true });
  const exitCode = await new Promise((resolveExit) => child.on('close', (code, signalName) => resolveExit({ code, signalName })));
  clearTimeout(timer);
  signal?.removeEventListener('abort', abort);
  try {
    if (timedOut) return { result: null, failure: { reasons: ['timeout'] } };
    if (signal?.aborted) return { result: null, failure: { reasons: ['cancellation'] } };
    if (exitCode.code !== 0) return { result: null, failure: { reasons: ['toolkit-failure'], detail: stderr } };
    if (!existsSync(outputPath)) return { result: null, failure: { reasons: ['missing-artifact'] } };
    return { result: JSON.parse(await readFile(outputPath, 'utf8')), failure: null };
  } catch {
    return { result: null, failure: { reasons: ['invalid-result'] } };
  } finally {
    await rm(workDir, { recursive: true, force: true });
  }
}

export async function runComparison({ config, baseline, candidate, signal }) {
  const selected = baseline ?? selectBaseline(config.baselines);
  if (!selected) return { status: 'inconclusive', reasons: ['no-baseline'] };
  const request = {
    suite: config.suite,
    baseline: { releaseId: selected.releaseId, services: selected.services ?? [] },
    candidate: { releaseId: candidate.releaseId, services: candidate.services ?? [] },
    environment: config.environment,
    runner: config.runner,
    fixture: config.fixture,
    dependencyMode: config.dependencyMode,
    conditions: { warmupRuns: config.warmupRuns, repetitions: config.repetitions, ...config.cache },
    stages: config.stages,
    publish: Boolean(config.publish),
  };
  const invoked = await invokeCompare({ config, request, signal });
  return normalizeComparison({ config, baseline: selected, candidate, result: invoked.result ?? {}, failure: invoked.failure });
}

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i].startsWith('--')) args[argv[i].slice(2)] = argv[++i];
  }
  return args;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const args = parseArgs(process.argv.slice(2));
  if (!args.config || !args.candidate) {
    console.error('usage: toolkit-adapter.mjs --config <path> --candidate <json-path> [--baseline <json-path>]');
    process.exitCode = 2;
  } else {
    const config = await readConfig(resolve(args.config));
    const candidate = JSON.parse(await readFile(resolve(args.candidate), 'utf8'));
    const baseline = args.baseline ? JSON.parse(await readFile(resolve(args.baseline), 'utf8')) : null;
    console.log(JSON.stringify(await runComparison({ config, baseline, candidate })));
  }
}
