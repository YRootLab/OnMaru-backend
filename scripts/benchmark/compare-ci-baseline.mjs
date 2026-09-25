#!/usr/bin/env node

import { readFile, writeFile } from 'node:fs/promises';

const IDENTITY_FIELDS = ['runnerImage', 'dependencyMode', 'cacheState', 'javaVersion', 'pythonVersion'];
const REQUIRED_METRICS = ['verifyWallClockMedianMillis', 'verifyWorkMedianMillis', 'peakRssBytes'];

function fail(message) {
  throw new Error(`CI baseline comparison ${message}`);
}

function round(value) {
  return Number(value.toFixed(6));
}

function requireFinite(value, field) {
  if (!Number.isFinite(value) || value < 0) fail(`${field} must be a non-negative number`);
}

function validateBaseline(baseline, label) {
  if (!baseline || typeof baseline !== 'object') fail(`${label} is required`);
  if (baseline.schemaVersion !== 1 || baseline.status !== 'valid') fail(`${label} must be a valid schemaVersion 1 baseline`);
  if (baseline.suite !== 'verify-serial') fail(`${label}.suite must be verify-serial`);
  if (baseline.runCount !== 3) fail(`${label}.runCount must be exactly 3`);
  if (!baseline.identity || typeof baseline.identity !== 'object') fail(`${label}.identity is required`);
  for (const field of ['commitSha', ...IDENTITY_FIELDS]) {
    if (typeof baseline.identity[field] !== 'string' || !baseline.identity[field]) fail(`${label}.identity.${field} is required`);
  }
  if (!baseline.metrics || typeof baseline.metrics !== 'object') fail(`${label}.metrics is required`);
  requireFinite(baseline.metrics.verifyWallClockMedianMillis, `${label}.metrics.verifyWallClockMedianMillis`);
  for (const metric of REQUIRED_METRICS.slice(1)) {
    const value = baseline.metrics[metric];
    if (value !== null) requireFinite(value, `${label}.metrics.${metric}`);
  }
  if (!['available', 'unavailable-from-actions-api'].includes(baseline.resourceEvidence)) fail(`${label}.resourceEvidence is invalid`);
  if (baseline.resourceEvidence === 'unavailable-from-actions-api' && (baseline.metrics.verifyWorkMedianMillis !== null || baseline.metrics.peakRssBytes !== null)) fail(`${label} has inconsistent unavailable resource evidence`);
}

function comparableIdentity(baseline, candidate) {
  for (const field of IDENTITY_FIELDS) {
    if (baseline.identity[field] !== candidate.identity[field]) fail(`not comparable: ${field}`);
  }
  if (baseline.resourceEvidence !== candidate.resourceEvidence) fail('not comparable: resourceEvidence');
}

function metricComparison(baseline, candidate) {
  if (baseline === null || candidate === null) return null;
  const absoluteDelta = candidate - baseline;
  return { baseline, candidate, absoluteDelta, relativeDelta: round(absoluteDelta / baseline) };
}

export function compareCiBaselines({ baseline, candidate }) {
  validateBaseline(baseline, 'baseline');
  validateBaseline(candidate, 'candidate');
  if (baseline.suite !== candidate.suite) fail('not comparable: suite');
  comparableIdentity(baseline, candidate);

  const wallClock = metricComparison(baseline.metrics.verifyWallClockMedianMillis, candidate.metrics.verifyWallClockMedianMillis);
  return {
    schemaVersion: 1,
    status: wallClock.absoluteDelta < 0 ? 'improved' : wallClock.absoluteDelta > 0 ? 'regressed' : 'unchanged',
    suite: baseline.suite,
    baseline: { commitSha: baseline.identity.commitSha, runCount: baseline.runCount, artifactUrls: baseline.artifactUrls },
    candidate: { commitSha: candidate.identity.commitSha, runCount: candidate.runCount, artifactUrls: candidate.artifactUrls },
    identity: Object.fromEntries(IDENTITY_FIELDS.map((field) => [field, baseline.identity[field]])),
    metrics: {
      verifyWallClockMedianMillis: wallClock,
      verifyWorkMedianMillis: metricComparison(baseline.metrics.verifyWorkMedianMillis, candidate.metrics.verifyWorkMedianMillis),
      peakRssBytes: metricComparison(baseline.metrics.peakRssBytes, candidate.metrics.peakRssBytes),
    },
    resourceEvidence: baseline.resourceEvidence,
  };
}

function formatDuration(milliseconds) {
  const totalSeconds = Math.round(milliseconds / 1_000);
  return `${Math.floor(totalSeconds / 60)}분 ${totalSeconds % 60}초`;
}

function formatPercent(value) {
  return `${Math.abs(value * 100).toFixed(2)}%`;
}

function statusText(status) {
  return { improved: '단축', regressed: '증가', unchanged: '변화 없음' }[status];
}

function metricRow(name, metric, formatter) {
  if (metric === null) return `| ${name} | 수집 불가 | 수집 불가 | 수집 불가 |`;
  const direction = metric.absoluteDelta < 0 ? '감소' : metric.absoluteDelta > 0 ? '증가' : '변화 없음';
  return `| ${name} | ${formatter(metric.baseline)} | ${formatter(metric.candidate)} | ${direction} ${formatPercent(metric.relativeDelta)} |`;
}

export function renderComparisonMarkdown(comparison) {
  const wallClock = comparison.metrics.verifyWallClockMedianMillis;
  const limitation = comparison.resourceEvidence === 'unavailable-from-actions-api'
    ? 'GitHub Actions API만으로는 CPU·메모리 수치를 수집할 수 없습니다. 이 비교는 실행 시간 기준이며, 값이 없는 자원 수치를 추정하지 않습니다.'
    : '실행 시간과 수집된 자원 수치를 함께 비교했습니다.';
  return `# CI 기준선 비교\n\n동일한 실행 조건에서 각각 세 번 수행한 CI 결과의 중앙값을 비교했습니다. 이번 결과는 **${statusText(comparison.status)}**입니다.\n\n| 항목 | 이전 기준선 | 비교 대상 | 변화 |\n| --- | ---: | ---: | --- |\n${metricRow('검증 완료 시간', wallClock, formatDuration)}\n${metricRow('CPU 작업 시간', comparison.metrics.verifyWorkMedianMillis, formatDuration)}\n${metricRow('최대 메모리 사용량', comparison.metrics.peakRssBytes, (bytes) => `${bytes} bytes`)}\n\n- 이전 기준선: ${comparison.baseline.commitSha} (${comparison.baseline.runCount}회)\n- 비교 대상: ${comparison.candidate.commitSha} (${comparison.candidate.runCount}회)\n- 실행 조건: ${comparison.identity.runnerImage}, ${comparison.identity.dependencyMode}, cache=${comparison.identity.cacheState}, Java ${comparison.identity.javaVersion}, Python ${comparison.identity.pythonVersion}\n\n${limitation}\n`;
}

function option(args, name) {
  const index = args.indexOf(name);
  return index < 0 ? undefined : args[index + 1];
}

async function main() {
  const args = process.argv.slice(2);
  const baselinePath = option(args, '--baseline');
  const candidatePath = option(args, '--candidate');
  const jsonOutput = option(args, '--json-output');
  const markdownOutput = option(args, '--markdown-output');
  if (!baselinePath || !candidatePath || !jsonOutput || !markdownOutput) fail('usage: compare-ci-baseline.mjs --baseline <before.json> --candidate <after.json> --json-output <comparison.json> --markdown-output <comparison.md>');
  const [baseline, candidate] = await Promise.all([baselinePath, candidatePath].map(async (path) => JSON.parse(await readFile(path, 'utf8'))));
  const comparison = compareCiBaselines({ baseline, candidate });
  await Promise.all([
    writeFile(jsonOutput, `${JSON.stringify(comparison, null, 2)}\n`),
    writeFile(markdownOutput, renderComparisonMarkdown(comparison)),
  ]);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
