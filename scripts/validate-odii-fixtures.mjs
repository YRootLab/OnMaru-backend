#!/usr/bin/env node
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { validateOdiiFixtureSet } from './lib/odii-fixture-validation.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '..');
const manifestPath = path.join(rootDir, 'docs/reference-snapshots/odii/manifest.json');
const fixtureDir = path.join(rootDir, 'testing/fixtures/provider/odii');

try {
  const result = await validateOdiiFixtureSet({ rootDir, manifestPath, fixtureDir });
  console.log(`Validated ${result.fixtureCount} Odii fixtures: ${result.scenarios.join(', ')}`);
} catch (error) {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
}
