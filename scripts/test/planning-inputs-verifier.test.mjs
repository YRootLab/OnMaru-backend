import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { sha256Hex, verifyPlanningInputs } from '../lib/planning-inputs-verifier.mjs';

function fixtureRepo() {
  const root = mkdtempSync(join(tmpdir(), 'planning-inputs-'));
  mkdirSync(join(root, 'docs/reference-snapshots/planning-inputs'), { recursive: true });
  mkdirSync(join(root, 'docs/contracts/fixtures'), { recursive: true });
  return root;
}

function writeManifest(root, entries) {
  writeFileSync(
    join(root, 'docs/reference-snapshots/planning-inputs/manifest.json'),
    `${JSON.stringify({ version: 1, generated_at: '2026-09-14T00:00:00.000Z', entries }, null, 2)}\n`,
  );
}

test('accepts a complete manifest whose snapshot hashes match repository-local files', () => {
  const root = fixtureRepo();
  const body = '# Backend input\n';
  writeFileSync(join(root, 'docs/reference-snapshots/planning-inputs/backend_schema_design_guide.md'), body);
  writeManifest(root, [
    {
      snapshot_path: 'docs/reference-snapshots/planning-inputs/backend_schema_design_guide.md',
      source_repository: 'https://github.com/YRootLab/OnMaru-docs.git',
      source_commit: '0123456789abcdef0123456789abcdef01234567',
      source_path: 'backend_schema_design_guide.md',
      sha256: sha256Hex(body),
    },
  ]);

  assert.deepEqual(verifyPlanningInputs(root), { ok: true, errors: [] });
});

test('rejects a snapshot when the manifest hash no longer matches file content', () => {
  const root = fixtureRepo();
  writeFileSync(join(root, 'docs/reference-snapshots/planning-inputs/backend_schema_design_guide.md'), 'changed\n');
  writeManifest(root, [
    {
      snapshot_path: 'docs/reference-snapshots/planning-inputs/backend_schema_design_guide.md',
      source_repository: 'https://github.com/YRootLab/OnMaru-docs.git',
      source_commit: '0123456789abcdef0123456789abcdef01234567',
      source_path: 'backend_schema_design_guide.md',
      sha256: sha256Hex('original\n'),
    },
  ]);

  const result = verifyPlanningInputs(root);

  assert.equal(result.ok, false);
  assert.match(result.errors.join('\n'), /sha256 mismatch/);
});

test('rejects manifest entries missing provenance fields', () => {
  const root = fixtureRepo();
  writeFileSync(join(root, 'docs/reference-snapshots/planning-inputs/input.md'), 'body\n');
  writeManifest(root, [
    {
      snapshot_path: 'docs/reference-snapshots/planning-inputs/input.md',
      sha256: sha256Hex('body\n'),
    },
  ]);

  const result = verifyPlanningInputs(root);

  assert.equal(result.ok, false);
  assert.match(result.errors.join('\n'), /source_repository/);
  assert.match(result.errors.join('\n'), /source_commit/);
  assert.match(result.errors.join('\n'), /source_path/);
});

test('rejects snapshot files that are not listed in the manifest', () => {
  const root = fixtureRepo();
  const body = 'tracked\n';
  writeFileSync(join(root, 'docs/reference-snapshots/planning-inputs/input.md'), body);
  writeFileSync(join(root, 'docs/reference-snapshots/planning-inputs/untracked.md'), 'untracked\n');
  writeManifest(root, [
    {
      snapshot_path: 'docs/reference-snapshots/planning-inputs/input.md',
      source_repository: 'https://github.com/YRootLab/OnMaru-docs.git',
      source_commit: '0123456789abcdef0123456789abcdef01234567',
      source_path: 'input.md',
      sha256: sha256Hex(body),
    },
  ]);

  const result = verifyPlanningInputs(root);

  assert.equal(result.ok, false);
  assert.match(result.errors.join('\n'), /not listed in manifest/);
});

test('rejects secrets in planning snapshots and contract fixtures', () => {
  const root = fixtureRepo();
  const body = 'safe input\n';
  writeFileSync(join(root, 'docs/reference-snapshots/planning-inputs/input.md'), body);
  writeFileSync(join(root, 'docs/contracts/fixtures/leaky.json'), '{"api_key":"sk-test-leaked"}\n');
  writeManifest(root, [
    {
      snapshot_path: 'docs/reference-snapshots/planning-inputs/input.md',
      source_repository: 'https://github.com/YRootLab/OnMaru-docs.git',
      source_commit: '0123456789abcdef0123456789abcdef01234567',
      source_path: 'input.md',
      sha256: sha256Hex(body),
    },
  ]);

  const result = verifyPlanningInputs(root);

  assert.equal(result.ok, false);
  assert.match(result.errors.join('\n'), /secret-like value/);
});
