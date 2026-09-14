#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { chmodSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const repoRoot = resolve(import.meta.dirname, '..');
const outputDir = process.env.AZIMUTT_OUTPUT_DIR
  ? resolve(process.env.AZIMUTT_OUTPUT_DIR)
  : resolve(repoRoot, 'docs/database/azimutt');
const dbmlEntry = resolve(repoRoot, 'docs/database/schema.dbml');

function toAzimuttSql(postgresSql) {
  return postgresSql
    .replace(/CREATE EXTENSION[^;]+;\n/gi, '')
    .replace(/COMMENT ON COLUMN[\s\S]*?;\n/g, '')
    .replace(/CREATE (?:UNIQUE )?INDEX[^;]+;\n/g, '')
    .replace(/ DEFERRABLE INITIALLY (?:DEFERRED|IMMEDIATE)/g, '')
    .replace(/\bUSING GIST\b/g, 'USING BTREE')
    .trim();
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function toAzimuttStrictSql(postgresSql) {
  const enumNames = [...postgresSql.matchAll(/CREATE TYPE "([^"]+)" AS ENUM/g)].map((match) => match[1]);
  let sql = postgresSql
    .replace(/CREATE TYPE[\s\S]*?;\n\n/g, '')
    .replace(/CREATE EXTENSION[^;]+;\n/gi, '')
    .replace(/COMMENT ON COLUMN[\s\S]*?;\n/g, '')
    .replace(/CREATE (?:UNIQUE )?INDEX[^;]+;\n/g, '')
    .replace(/ DEFERRABLE INITIALLY (?:DEFERRED|IMMEDIATE)/g, '')
    .replace(/\bUSING GIST\b/g, 'USING BTREE');

  const typeReplacements = [
    ['timestamptz', 'timestamp'],
    ['uuid', 'varchar(36)'],
    ['jsonb', 'text'],
    ['geography', 'text'],
    ['geometry', 'text'],
    ['vector', 'text'],
  ];

  for (const [fromType, toType] of typeReplacements) {
    sql = sql.replace(new RegExp(`("[^"]+"\\s+)${fromType}\\b`, 'gi'), `$1${toType}`);
  }

  for (const enumName of enumNames) {
    sql = sql.replace(new RegExp(`("[^"]+"\\s+)${escapeRegExp(enumName)}\\b`, 'g'), '$1varchar(32)');
  }

  return sql.trim();
}

const postgresSql = execFileSync('npx', ['-y', '-p', '@dbml/cli', 'dbml2sql', dbmlEntry, '--postgres'], {
  encoding: 'utf8',
  maxBuffer: 1024 * 1024 * 20,
});

mkdirSync(outputDir, { recursive: true });
writeFileSync(resolve(outputDir, 'onmaru-schema.azimutt.sql'), `${toAzimuttSql(postgresSql)}\n`);
writeFileSync(resolve(outputDir, 'onmaru-schema.azimutt-strict.sql'), `${toAzimuttStrictSql(postgresSql)}\n`);
writeFileSync(resolve(outputDir, 'onmaru-schema.postgres.sql'), `${postgresSql.trim()}\n`);
chmodSync(new URL(import.meta.url), 0o755);

console.log(`Generated Azimutt artifacts in ${outputDir}`);
console.log('- onmaru-schema.azimutt.sql');
console.log('- onmaru-schema.azimutt-strict.sql');
console.log('- onmaru-schema.postgres.sql');
