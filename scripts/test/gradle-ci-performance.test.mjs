import assert from 'node:assert/strict';
import { existsSync, readFileSync, mkdtempSync, mkdirSync, writeFileSync, copyFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { selectPlan, commandArguments } from '../benchmark/pipeline-experiment.mjs';

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

test('committed benchmark plans and shared Java CI actually apply the Gradle profile', { timeout: 240000 }, () => {
  const workflow = spawnSync('ruby', ['-r', 'yaml', '-r', 'json', '-e', 'puts JSON.generate(YAML.load_file(ARGV[0]))', '.github/workflows/ci.yml'], { encoding: 'utf8' });
  assert.equal(workflow.status, 0, workflow.stderr);
  const javaRun = JSON.parse(workflow.stdout).jobs.java.steps.find(step => step.run?.includes('./gradlew')).run;
  const cases = [
    { name: 'ci benchmark', argv: commandArguments(selectPlan(Buffer.from(read('.github/pipeline-benchmark-test-plan.json')), 'ci').commands[0], { max_workers: 2, build_cache: false }), workers: 2, cache: false },
    { name: 'test benchmark', argv: commandArguments(selectPlan(Buffer.from(read('.github/pipeline-benchmark-test-plan.json')), 'test').commands[0], { max_workers: 3, build_cache: true }), workers: 3, cache: true },
    { name: 'shared Java CI', argv: javaRun.replace(/\\\n/g, ' ').trim().split(/\s+/), workers: 4, cache: true },
  ];
  const failures = [];
  for (const entry of cases) {
    const directory = mkdtempSync(join(tmpdir(), 'onmaru-effective-gradle-'));
    try {
      mkdirSync(join(directory, 'build-logic'));
      writeFileSync(join(directory, 'settings.gradle.kts'), 'rootProject.name = "effective-profile-fixture"\n');
      copyFileSync(join(root, 'gradle.properties'), join(directory, 'gradle.properties'));
      copyFileSync(join(root, 'build-logic/ci-performance.gradle.kts'), join(directory, 'build-logic/ci-performance.gradle.kts'));
      // Replace application tasks with the profile's diagnostic task, retaining
      // every production launch option. No project dependencies are resolved.
      const options = entry.argv.slice(1).filter(arg => !arg.startsWith(':'));
      const executed = spawnSync(join(root, entry.argv[0]), [...options, '--offline', '--project-dir', directory, 'ciPerformanceProfile'], {
        cwd: root, encoding: 'utf8', timeout: 75000, maxBuffer: 1024 * 1024,
      });
      if (executed.status !== 0) {
        failures.push(`${entry.name}: ${executed.stderr || executed.stdout}`);
        continue;
      }
      const actual = JSON.parse(readFileSync(join(directory, 'build/ci-performance/gradle-profile.json'), 'utf8'));
      assert.equal(actual.profileEnabled, true, entry.name);
      assert.equal(actual.effectiveWorkers, entry.workers, entry.name);
      assert.equal(actual.buildCacheEnabled, entry.cache, entry.name);
    } finally { rmSync(directory, { recursive: true, force: true }); }
  }
  assert.deepEqual(failures, [], 'Every production invocation must load and apply the trusted init script');
});
