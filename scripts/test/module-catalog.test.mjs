import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

test('benchmark catalog covers every current backend test entrypoint and full-suite paths', async () => {
  const catalog = JSON.parse(await readFile('.github/benchmark-modules.yml', 'utf8'));
  const ids = new Set(catalog.modules.map((module) => module.id));

  for (const id of ['tourism-api', 'persistence-jdbc', 'catalog', 'audio', 'community', 'identity', 'insights', 'journey', 'operations', 'shared-web', 'spring-api', 'ai']) {
    assert.ok(ids.has(id), `missing ${id}`);
  }
  assert.ok(catalog.always_full_paths.includes('build-logic/**'));
  assert.ok(catalog.always_full_paths.includes('.github/workflows/**'));
  assert.equal(catalog.modules.find((module) => module.id === 'spring-api').resource_profile, 'heavy');
  assert.equal(
    catalog.modules.find((module) => module.id === 'ai').test_command,
    'python3 -m pip install --user uv && export PATH="$HOME/.local/bin:$PATH" && cd ai && uv run pytest',
  );
});
