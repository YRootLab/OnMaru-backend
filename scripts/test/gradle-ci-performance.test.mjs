import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const root = process.cwd();

function read(path) {
  return readFileSync(join(root, path), 'utf8');
}

test('CI Gradle performance profile is opt-in and bounded', () => {
  const propertiesPath = 'gradle.properties';
  const initScriptPath = 'build-logic/ci-performance.gradle.kts';

  assert.ok(existsSync(join(root, propertiesPath)), 'gradle.properties must define CI profile inputs');
  assert.ok(existsSync(join(root, initScriptPath)), 'CI performance init script must exist');

  const properties = read(propertiesPath);
  assert.match(properties, /^onmaru\.ci\.performance\.enabled=false$/m);
  assert.match(properties, /^onmaru\.ci\.performance\.max-workers=4$/m);
  assert.match(properties, /^onmaru\.ci\.performance\.build-cache=true$/m);
  assert.doesNotMatch(properties, /^org\.gradle\.(?:parallel|caching|max\.workers)=/m);

  const initScript = read(initScriptPath);
  assert.match(initScript, /onmaru\.ci\.performance\.enabled/);
  assert.match(initScript, /onmaru\.ci\.performance\.max-workers/);
  assert.match(initScript, /onmaru\.ci\.performance\.build-cache/);
  assert.match(initScript, /maxWorkerCount/);
  assert.match(initScript, /isBuildCacheEnabled/);
  assert.match(initScript, /ciPerformanceProfile/);
  assert.match(initScript, /gradle-profile\.json/);
});
