import { readFile, writeFile } from 'node:fs/promises';

const allLanes = Object.freeze({ hygiene: true, java: true, contract: true, ai: true });
const globalPrefixes = ['.github/', 'build-logic/', 'gradle/', 'scripts/ci/', 'scripts/test/'];
const globalFiles = new Set([
  'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat', 'scripts/verify-contracts',
]);

export function selectCiLanes(changedFiles) {
  const files = [...new Set(changedFiles.map((file) => file.trim()).filter(Boolean))];
  if (files.length === 0 || files.some((file) => globalFiles.has(file) || globalPrefixes.some((prefix) => file.startsWith(prefix)))) {
    return { ...allLanes };
  }

  return {
    hygiene: true,
    java: files.some((file) => /^(apps|adapters|modules)\//.test(file)),
    contract: true,
    ai: files.some((file) => file.startsWith('ai/')),
  };
}

function option(args, name) {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : undefined;
}

async function main() {
  const args = process.argv.slice(2);
  const changedFilesPath = option(args, '--changed-files');
  const githubOutputPath = option(args, '--github-output');
  if (!changedFilesPath) throw new Error('--changed-files is required');

  const lanes = selectCiLanes((await readFile(changedFilesPath, 'utf8')).split(/\r?\n/));
  const output = Object.entries(lanes).map(([name, enabled]) => `${name}=${enabled}`).join('\n') + '\n';
  if (githubOutputPath) await writeFile(githubOutputPath, output, { flag: 'a' });
  else process.stdout.write(output);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
