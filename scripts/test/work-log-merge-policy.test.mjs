import { readFileSync } from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';

const REQUIRED_UNION_DOCS = [
  'CHANGELOG.md',
  'handoff.md',
  'improvements.md',
];

function parseGitAttributes(text) {
  const entries = new Map();
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) {
      continue;
    }
    const [pattern, ...attributes] = trimmed.split(/\s+/);
    entries.set(pattern, new Set(attributes));
  }
  return entries;
}

test('shared work-log documents use union merge to reduce parallel PR conflicts', () => {
  const attributes = parseGitAttributes(readFileSync('.gitattributes', 'utf8'));

  for (const documentPath of REQUIRED_UNION_DOCS) {
    assert.ok(
      attributes.get(documentPath)?.has('merge=union'),
      `${documentPath} must be configured with merge=union`,
    );
  }
});

test('merge conflict temporary artifacts are ignored', () => {
  const ignoredPatterns = new Set(
    readFileSync('.gitignore', 'utf8')
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean),
  );

  assert.ok(ignoredPatterns.has('*.orig'), '*.orig must be ignored');
  assert.ok(ignoredPatterns.has('*.rej'), '*.rej must be ignored');
});
