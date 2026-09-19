import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const root = process.cwd();

function read(path) {
  return readFileSync(join(root, path), 'utf8');
}

const javaLibraryProjects = [
  'modules/audio/build.gradle.kts',
  'modules/catalog/build.gradle.kts',
  'modules/community/build.gradle.kts',
  'modules/identity/build.gradle.kts',
  'modules/insights/build.gradle.kts',
  'modules/journey/build.gradle.kts',
  'modules/operations/build.gradle.kts',
  'modules/shared-web/build.gradle.kts',
  'adapters/persistence-jdbc/build.gradle.kts',
  'adapters/tourism-api/build.gradle.kts',
];

const hiddenDependencyPattern = /dependencies\s*\{|testImplementation|implementation\(|api\(|runtimeOnly|platform\(/;

test('Gradle convention plugins keep dependency declarations module-local', () => {
  assert.ok(existsSync(join(root, 'build-logic/settings.gradle.kts')), 'build-logic settings must exist');
  assert.ok(existsSync(join(root, 'build-logic/build.gradle.kts')), 'build-logic build file must exist');

  const settings = read('settings.gradle.kts');
  assert.match(settings, /includeBuild\("build-logic"\)/, 'settings.gradle.kts must include build-logic');

  const javaConvention = read('build-logic/src/main/kotlin/onmaru.java-library-conventions.gradle.kts');
  assert.match(javaConvention, /`java-library`/, 'Java library convention must apply java-library');
  assert.match(javaConvention, /languageVersion = JavaLanguageVersion\.of\(21\)/, 'Java 21 toolchain must be shared');
  assert.match(javaConvention, /useJUnitPlatform\(\)/, 'JUnit Platform task setup must be shared');
  assert.doesNotMatch(
    javaConvention,
    hiddenDependencyPattern,
    'Java library convention must not hide module dependency declarations',
  );

  const appConvention = read('build-logic/src/main/kotlin/onmaru.spring-boot-app-conventions.gradle.kts');
  assert.match(appConvention, /java/, 'Spring Boot app convention must apply the Java plugin');
  assert.match(appConvention, /languageVersion = JavaLanguageVersion\.of\(21\)/, 'Java 21 toolchain must be shared');
  assert.match(appConvention, /useJUnitPlatform\(\)/, 'JUnit Platform task setup must be shared');
  assert.doesNotMatch(
    appConvention,
    hiddenDependencyPattern,
    'Spring Boot app convention must not hide app dependency declarations',
  );

  for (const projectFile of javaLibraryProjects) {
    const buildFile = read(projectFile);
    assert.match(
      buildFile,
      /id\("onmaru\.java-library-conventions"\)/,
      `${projectFile} must apply the Java library convention`,
    );
    assert.match(buildFile, /dependencies\s*\{/, `${projectFile} must keep its dependencies block visible`);
    assert.doesNotMatch(
      buildFile,
      /tasks\.withType<Test>/,
      `${projectFile} must not repeat shared JUnit task configuration`,
    );
  }

  const appBuildFile = read('apps/spring-api/build.gradle.kts');
  assert.match(
    appBuildFile,
    /id\("onmaru\.spring-boot-app-conventions"\)/,
    'spring-api must apply the Spring Boot app convention',
  );
  assert.match(appBuildFile, /dependencies\s*\{/, 'spring-api must keep its dependencies block visible');
  assert.doesNotMatch(
    appBuildFile,
    /tasks\.withType<Test>/,
    'spring-api must not repeat shared JUnit task configuration',
  );
});

test('module governance document records current dependency direction', () => {
  const docPath = 'docs/architecture/spring-gradle-modules.md';
  assert.ok(existsSync(join(root, docPath)), 'module governance document must exist');

  const doc = read(docPath);
  for (const requiredText of [
    'apps:spring-api -> modules:*',
    'apps:spring-api -> adapters:*',
    'adapters:* -> modules:*',
    'modules:audio -> modules:catalog',
    'modules:* -> apps:spring-api',
    'modules:* -> adapters:*',
    'adapters:* -> apps:spring-api',
    'Do not hide `api`, `implementation`, `testImplementation`',
  ]) {
    assert.ok(doc.includes(requiredText), `${docPath} must mention ${requiredText}`);
  }
});
