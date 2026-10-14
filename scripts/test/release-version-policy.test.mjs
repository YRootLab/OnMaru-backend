import test from 'node:test';
import assert from 'node:assert/strict';

import {
  SKIPPED_RELEASE_VERSIONS,
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

test('accepts a later patch when every skipped version is listed', () => {
  const skipped = ['v0.3.35', 'v0.3.36'];
  assert.equal(formatVersion(validateCandidate(tags, 'release/v0.3.37', skipped).candidate), 'v0.3.37');
});

test('rejects a later patch when a skipped version is not listed', () => {
  assert.throws(
    () => validateCandidate(tags, 'release/v0.3.37', ['v0.3.35']),
    /Expected v0\.3\.35, or a skip listed/,
  );
});

test('the default skip list covers v0.3.44 and v0.3.45 so v0.3.46 is accepted after v0.3.43', () => {
  const release = ['v0.3.43'];
  assert.equal(formatVersion(validateCandidate(release, 'release/v0.3.46').candidate), 'v0.3.46');
  assert.deepEqual(SKIPPED_RELEASE_VERSIONS, ['v0.3.44', 'v0.3.45']);
});

test('does not allow skipping across a different minor version', () => {
  assert.throws(() => validateCandidate(['v0.3.43'], 'release/v0.4.1'), /Expected v0\.3\.44/);
});
