import test from 'node:test';
import assert from 'node:assert/strict';

import {
  candidateVersion,
  formatVersion,
  latestVersion,
  nextPatch,
  validateCandidate,
} from '../release/version-policy.mjs';

const tags = ['v0.3.5', 'v0.3.13', 'v0.3.34'];

test('uses the highest existing tag and does not backfill historical gaps', () => {
  const latest = latestVersion(tags);
  assert.equal(formatVersion(latest), 'v0.3.34');
  assert.equal(formatVersion(nextPatch(latest)), 'v0.3.35');
});

test('accepts the next patch release ref with or without v prefix', () => {
  assert.deepEqual(candidateVersion('release/v0.3.35'), { major: 0, minor: 3, patch: 35 });
  assert.deepEqual(candidateVersion('release/0.3.35'), { major: 0, minor: 3, patch: 35 });
  assert.equal(formatVersion(validateCandidate(tags, 'release/v0.3.35').candidate), 'v0.3.35');
});

test('rejects a skipped release version', () => {
  assert.throws(
    () => validateCandidate(tags, 'release/v0.4.0'),
    /Expected v0\.3\.35/,
  );
});

test('rejects a release ref without a semantic version', () => {
  assert.throws(
    () => validateCandidate(tags, 'release/latest'),
    /Expected release\/vX\.Y\.Z/,
  );
});
