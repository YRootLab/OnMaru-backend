import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

const SECRET_PATTERNS = [
  /["']?serviceKey["']?\s*[:=]/i,
  /ODII_API_KEY/i,
  /TOUR_API_KEY/i,
  /KAKAO_(?:MAP_KEY|CLIENT_ID)/i,
  /GEMINI_API_KEY/i,
];

const URL_PATTERN = /https?:\/\/[^\s"'<>\\]+/g;
const SECRET_TOKEN_PATTERN = /^[A-Za-z0-9+/=_-]{80,}$/;

export function assertNoSecretText(text, context) {
  for (const pattern of SECRET_PATTERNS) {
    if (pattern.test(text)) {
      throw new Error(`secret-like value found in ${context}`);
    }
  }

  for (const match of text.matchAll(URL_PATTERN)) {
    const rawUrl = match[0];
    let url;
    try {
      url = new URL(rawUrl);
    } catch {
      continue;
    }
    for (const value of url.searchParams.values()) {
      if (SECRET_TOKEN_PATTERN.test(value)) {
        throw new Error(`secret-like value found in ${context}`);
      }
    }
  }
}

export async function readJsonFile(filePath) {
  const text = await readFile(filePath, 'utf8');
  assertNoSecretText(text, filePath);
  return JSON.parse(text);
}

export function sha256(text) {
  return createHash('sha256').update(text).digest('hex');
}

export async function validateOdiiFixtureSet({ manifestPath, fixtureDir }) {
  const manifestText = await readFile(manifestPath, 'utf8');
  assertNoSecretText(manifestText, manifestPath);

  const manifest = JSON.parse(manifestText);
  if (!Array.isArray(manifest.requiredScenarios) || manifest.requiredScenarios.length === 0) {
    throw new Error('manifest.requiredScenarios must be a non-empty array');
  }
  if (!Array.isArray(manifest.captures) || manifest.captures.length === 0) {
    throw new Error('manifest.captures must be a non-empty array');
  }

  const scenarios = new Set();
  for (const capture of manifest.captures) {
    if (!capture.scenario || !capture.fixture || !capture.sha256) {
      throw new Error('each manifest capture must include scenario, fixture, and sha256');
    }
    scenarios.add(capture.scenario);

    const fixturePath = path.join(fixtureDir, capture.fixture);
    const fixtureText = await readFile(fixturePath, 'utf8');
    assertNoSecretText(fixtureText, fixturePath);
    const actualHash = sha256(fixtureText);
    if (actualHash !== capture.sha256) {
      throw new Error(`fixture hash mismatch for ${capture.fixture}`);
    }

    JSON.parse(fixtureText);
  }

  const missing = manifest.requiredScenarios.filter((scenario) => !scenarios.has(scenario));
  if (missing.length > 0) {
    throw new Error(`missing required Odii scenarios: ${missing.join(', ')}`);
  }

  return {
    fixtureCount: manifest.captures.length,
    scenarios: [...scenarios].sort(),
  };
}
