import assert from 'node:assert/strict';
import test from 'node:test';

import { parsePublishMode, planRelationshipChanges } from '../lib/issue-graph-publish.mjs';

test('defaults to dry-run and requires an explicit apply flag for mutation', () => {
  assert.equal(parsePublishMode([]), 'dry-run');
  assert.equal(parsePublishMode(['--dry-run']), 'dry-run');
  assert.equal(parsePublishMode(['--apply']), 'apply');
  assert.throws(
    () => parsePublishMode(['--dry-run', '--apply']),
    /mutually exclusive/,
  );
});

test('plans exact blocked-by additions and removals', () => {
  assert.deepEqual(
    planRelationshipChanges([61, 62], [62, 63]),
    { add: [63], remove: [61] },
  );
});

test('normalizes duplicate and unordered relationship numbers', () => {
  assert.deepEqual(
    planRelationshipChanges([63, 61, 61], [62, 63, 62]),
    { add: [62], remove: [61] },
  );
});
