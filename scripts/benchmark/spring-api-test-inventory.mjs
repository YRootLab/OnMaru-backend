#!/usr/bin/env node

import { readdir, readFile, stat } from 'node:fs/promises';
import { join, relative, resolve } from 'node:path';

const SHARDS = ['unit-contract', 'postgres-catalog', 'postgres-audio', 'postgres-other'];
const KNOWN_UNIT_ROOTS = new Set([
  'architecture', 'config', 'integration', 'internal', 'journey', 'observability', 'operations',
  'r1', 'r2', 'scheduling', 'security', 'tourism', 'web', 'worker',
]);

function className(relativePath) {
  return relativePath
    .replaceAll('\\', '/')
    .replace(/^.*?java\//, '')
    .replace(/\.java$/, '')
    .split('/')
    .join('.');
}

function classify(relativePath) {
  const normalized = relativePath.replaceAll('\\', '/');
  const name = className(normalized);
  const file = normalized.split('/').at(-1) ?? '';
  if (normalized.includes('/tourism/audio/')) return 'postgres-audio';
  const isPostgres = normalized.includes('/testing/postgres/');
  if (!isPostgres) {
    const packageParts = name.split('.');
    const root = packageParts[3];
    if (packageParts.length === 4 && file.endsWith('Tests.java')) return 'unit-contract';
    if (KNOWN_UNIT_ROOTS.has(root)) return 'unit-contract';
    return null;
  }
  if (/Catalog|TourApiCatalog|TourApiLiveCatalog/.test(file)) return 'postgres-catalog';
  if (/Audio|Odii/.test(file) || normalized.includes('/tourism/audio/')) return 'postgres-audio';
  return 'postgres-other';
}

export function buildSpringApiInventory(files) {
  const shards = Object.fromEntries(SHARDS.map((id) => [id, { id, tests: [] }]));
  const unclassified = [];
  for (const file of [...files].sort()) {
    const shard = classify(file);
    const name = className(file);
    if (!shard) {
      unclassified.push(name);
      continue;
    }
    shards[shard].tests.push(name);
  }
  for (const shard of Object.values(shards)) shard.tests.sort();
  return {
    schemaVersion: 1,
    totalTests: Object.values(shards).reduce((sum, shard) => sum + shard.tests.length, 0),
    shards,
    unclassified,
  };
}

async function listJavaFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) files.push(...await listJavaFiles(path));
    else if (entry.isFile() && entry.name.endsWith('Tests.java')) files.push(path);
  }
  return files;
}

export async function discoverSpringApiTests(root) {
  const sourceRoot = resolve(root, 'apps/spring-api/src/test/java');
  const files = await listJavaFiles(sourceRoot);
  return buildSpringApiInventory(files.map((file) => relative(resolve(root), file)));
}

async function main() {
  const rootIndex = process.argv.indexOf('--root');
  const outputIndex = process.argv.indexOf('--output');
  const root = rootIndex < 0 ? process.cwd() : process.argv[rootIndex + 1];
  const output = outputIndex < 0 ? 'spring-api-test-inventory.json' : process.argv[outputIndex + 1];
  if (!root || !output) throw new Error('usage: spring-api-test-inventory.mjs --root <repository> --output <json>');
  const inventory = await discoverSpringApiTests(root);
  if (inventory.unclassified.length > 0) throw new Error(`unclassified Spring API tests: ${inventory.unclassified.join(', ')}`);
  const outputPath = resolve(output);
  await stat(resolve(root, 'apps/spring-api/src/test/java'));
  await readFile(resolve(root, 'settings.gradle.kts'), 'utf8');
  await import('node:fs/promises').then(({ writeFile }) => writeFile(outputPath, `${JSON.stringify(inventory, null, 2)}\n`));
}

if (import.meta.url === `file://${process.argv[1]}`) main().catch((error) => { console.error(error.message); process.exitCode = 1; });
