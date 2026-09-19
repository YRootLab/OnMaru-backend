#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { validateOdiiFixtureSet } from './lib/odii-fixture-validation.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '..');
const fixtureDir = path.join(rootDir, 'testing/fixtures/provider/odii');
const manifestPath = path.join(rootDir, 'docs/reference-snapshots/odii/manifest.json');

const scenarios = [
  {
    scenario: 'story_based_first_page',
    endpoint: 'storyBasedList',
    params: { langCode: 'ko', tid: '30', tlid: '102', pageNo: '1', numOfRows: '3' },
    description: 'Known spot story list first page with official Korean scripts and audio URLs.',
  },
  {
    scenario: 'story_based_second_page',
    endpoint: 'storyBasedList',
    params: { langCode: 'ko', tid: '30', tlid: '102', pageNo: '2', numOfRows: '3' },
    description: 'Same spot second page to pin provider pagination fields.',
  },
  {
    scenario: 'story_location_based',
    endpoint: 'storyLocationBasedList',
    params: { langCode: 'ko', mapX: '127.120688', mapY: '37.515577', radius: '1000', pageNo: '1', numOfRows: '3' },
    description: 'Location-based story lookup around Hanseong Baekje Museum.',
  },
  {
    scenario: 'story_search_hanok',
    endpoint: 'storySearchList',
    params: { langCode: 'ko', keyword: '한옥', pageNo: '1', numOfRows: '3' },
    description: 'Keyword search fixture for OnMaru hanok-related discovery.',
  },
  {
    scenario: 'story_search_hanok_en',
    endpoint: 'storySearchList',
    params: { langCode: 'en', keyword: 'hanok', pageNo: '1', numOfRows: '3' },
    description: 'English keyword search fixture to pin provider language parameter behavior.',
  },
  {
    scenario: 'empty_search',
    endpoint: 'storySearchList',
    params: { langCode: 'ko', keyword: 'ONMARU_NO_MATCH_20260914', pageNo: '1', numOfRows: '3' },
    description: 'Empty result fixture for no-match provider response shape.',
  },
  {
    scenario: 'empty_script_story',
    endpoint: 'storySearchList',
    params: { langCode: 'ko', keyword: '제주', pageNo: '3', numOfRows: '20' },
    description: 'Search page containing story stid=6566/stlid=18602 with empty script and empty audioUrl.',
  },
  {
    scenario: 'provider_error_missing_key',
    endpoint: 'storySearchList',
    params: { langCode: 'ko', keyword: '한옥', pageNo: '1', numOfRows: '1' },
    omitServiceKey: true,
    description: 'Provider error envelope when serviceKey is absent.',
  },
];

export function redactUrl(value) {
  const url = new URL(value);
  url.searchParams.delete('serviceKey');
  url.searchParams.delete('ServiceKey');
  return url.toString();
}

export async function loadDotEnv(envPath = path.join(rootDir, '.env.local')) {
  const text = await readFile(envPath, 'utf8');
  const values = {};
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const separator = line.indexOf('=');
    if (separator === -1) continue;
    values[line.slice(0, separator)] = line.slice(separator + 1);
  }
  return values;
}

function buildUrl({ baseUrl, apiKey, scenario }) {
  const url = new URL(`${baseUrl.replace(/\/$/, '')}/${scenario.endpoint}`);
  for (const [key, value] of Object.entries({
    MobileOS: 'ETC',
    MobileApp: 'OnMaru',
    _type: 'json',
    ...scenario.params,
  })) {
    url.searchParams.set(key, value);
  }
  if (!scenario.omitServiceKey) {
    url.searchParams.set('serviceKey', decodeURIComponent(apiKey));
  }
  return url;
}

async function requestProvider(url) {
  const response = await fetch(url, {
    headers: {
      accept: 'application/json, text/plain;q=0.9, */*;q=0.8',
    },
  });
  const text = await response.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    body = text;
  }

  return {
    status: response.status,
    headers: {
      'content-type': response.headers.get('content-type'),
      date: response.headers.get('date'),
    },
    body,
  };
}

function stableJson(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

function hashText(text) {
  return createHash('sha256').update(text).digest('hex');
}

async function writeFixture({ scenario, requestUrl, response }) {
  const fixture = {
    provider: 'KTO_ODII',
    scenario: scenario.scenario,
    capturedAt: new Date().toISOString(),
    request: {
      method: 'GET',
      endpoint: scenario.endpoint,
      url: redactUrl(requestUrl.toString()),
      params: {
        ...scenario.params,
        MobileOS: 'ETC',
        MobileApp: 'OnMaru',
        _type: 'json',
      },
    },
    response,
    notes: scenario.description,
  };
  const fileName = `${scenario.scenario}.json`;
  const text = stableJson(fixture);
  await writeFile(path.join(fixtureDir, fileName), text);
  return {
    scenario: scenario.scenario,
    fixture: fileName,
    sha256: hashText(text),
    endpoint: scenario.endpoint,
    redactedUrl: fixture.request.url,
    capturedAt: fixture.capturedAt,
    description: scenario.description,
  };
}

export async function captureOdiiFixtures() {
  const env = await loadDotEnv();
  const baseUrl = env.ODII_API_URL;
  const apiKey = env.ODII_API_KEY;
  if (!baseUrl || !apiKey) {
    throw new Error('ODII_API_URL and ODII_API_KEY must be set in .env.local');
  }

  await mkdir(fixtureDir, { recursive: true });
  await mkdir(path.dirname(manifestPath), { recursive: true });

  const captures = [];
  for (const scenario of scenarios) {
    const url = buildUrl({ baseUrl, apiKey, scenario });
    const response = await requestProvider(url);
    captures.push(await writeFixture({ scenario, requestUrl: url, response }));
  }

  const manifest = {
    provider: 'KTO_ODII',
    capturedAt: new Date().toISOString(),
    source: {
      name: '한국관광공사_관광지 오디오 가이드정보_GW',
      officialUrl: 'https://www.data.go.kr/data/15101971/openapi.do',
      baseUrl,
    },
    requiredScenarios: scenarios.map((scenario) => scenario.scenario),
    captures,
  };
  await writeFile(manifestPath, stableJson(manifest));

  return validateOdiiFixtureSet({ rootDir, manifestPath, fixtureDir });
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const result = await captureOdiiFixtures();
    console.log(`Captured and validated ${result.fixtureCount} Odii fixtures: ${result.scenarios.join(', ')}`);
  } catch (error) {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  }
}
