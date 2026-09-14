import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../..', import.meta.url));

describe('Flyway migration policy', () => {
  it('keeps migration version reservations and checksums in sync', () => {
    const registry = JSON.parse(readFileSync(join(root, 'db/migration/registry/migrations.json'), 'utf8'));
    const registeredPaths = new Set(registry.migrations.map((migration) => migration.path));
    const migrationFiles = readdirSync(join(root, 'apps/spring-api/src/main/resources/db/migration/baseline'))
      .filter((file) => /^V\d{3}__.+\.sql$/.test(file))
      .map((file) => `apps/spring-api/src/main/resources/db/migration/baseline/${file}`);

    assert.deepEqual(migrationFiles.sort(), [...registeredPaths].sort(), 'Every Flyway migration file must be registered exactly once');

    for (const migration of registry.migrations) {
      const fileVersion = migration.path.match(/\/V(\d{3})__[^/]+\.sql$/)?.[1];
      assert.equal(fileVersion, migration.version, `${migration.path} filename version must match registry version`);

      const sql = readFileSync(join(root, migration.path), 'utf8');
      const actualSha256 = createHash('sha256').update(sql).digest('hex');
      assert.equal(migration.sha256, actualSha256, `${migration.path} sha256 must match registry`);
    }

    const versions = registry.migrations.map((migration) => migration.version);
    assert.equal(new Set(versions).size, versions.length, 'Flyway migration versions must be unique');
  });
});
