import { execFileSync } from 'node:child_process';

const VERSION_PATTERN = /^v(\d+)\.(\d+)\.(\d+)$/;
const RELEASE_REF_PATTERN = /^release\/(?:v)?(\d+)\.(\d+)\.(\d+)$/;

export function parseVersion(value) {
  const match = VERSION_PATTERN.exec(value);
  if (!match) return null;
  return { major: Number(match[1]), minor: Number(match[2]), patch: Number(match[3]) };
}

export function compareVersions(left, right) {
  return left.major - right.major || left.minor - right.minor || left.patch - right.patch;
}

export function nextPatch(version) {
  return { major: version.major, minor: version.minor, patch: version.patch + 1 };
}

export function formatVersion(version) {
  return `v${version.major}.${version.minor}.${version.patch}`;
}

export function latestVersion(tags) {
  const versions = tags.map(parseVersion).filter(Boolean);
  if (versions.length === 0) return null;
  return versions.sort(compareVersions).at(-1);
}

export function candidateVersion(ref) {
  const match = RELEASE_REF_PATTERN.exec(ref ?? '');
  if (!match) return null;
  return { major: Number(match[1]), minor: Number(match[2]), patch: Number(match[3]) };
}

/**
 * 태그 없이 건너뛴 릴리스 버전 목록이다. 이 목록의 버전은 다음 릴리스가 건너뛸 수 있다.
 * - v0.3.44: master에 릴리스되었지만 태그가 없다.
 * - v0.3.45: 릴리스가 취소되었다.
 * - v0.3.46: master에 릴리스되었지만 태그가 없다.
 * - v0.3.47: master에 릴리스되었지만 태그가 없다.
 * - v0.3.48: master에 릴리스되었지만 태그가 없다.
 * - v0.3.49: master에 릴리스되었지만 태그가 없다.
 */
export const SKIPPED_RELEASE_VERSIONS = ['v0.3.44', 'v0.3.45', 'v0.3.46', 'v0.3.47', 'v0.3.48', 'v0.3.49'];

export function validateCandidate(tags, ref, skippedVersions = SKIPPED_RELEASE_VERSIONS) {
  const latest = latestVersion(tags);
  if (!latest) throw new Error('No semantic version tag was found. A release must start from an approved baseline.');

  const candidate = candidateVersion(ref);
  if (!candidate) throw new Error(`Invalid release ref: ${ref}. Expected release/vX.Y.Z.`);

  const expected = nextPatch(latest);
  if (compareVersions(candidate, expected) === 0) {
    return { latest, expected, candidate };
  }

  // 건너뛰기는 expected부터 candidate 직전까지의 모든 버전이 명시 목록에 있을 때만 허용한다.
  const skipped = new Set(skippedVersions);
  const gapIsAllowed = compareVersions(candidate, expected) > 0
    && candidate.major === expected.major
    && candidate.minor === expected.minor
    && Array.from({ length: candidate.patch - expected.patch }, (_, index) =>
      formatVersion({ ...expected, patch: expected.patch + index })).every((version) => skipped.has(version));
  if (!gapIsAllowed) {
    throw new Error(
      `Release version ${formatVersion(candidate)} is not the next patch after ${formatVersion(latest)}. `
        + `Expected ${formatVersion(expected)}, or a skip listed in SKIPPED_RELEASE_VERSIONS.`,
    );
  }

  return { latest, expected, candidate, skipped: formatVersion(expected) };
}

function repositoryTags() {
  return execFileSync('git', ['tag', '--list', 'v*'], { encoding: 'utf8' })
    .split('\n')
    .map((tag) => tag.trim())
    .filter(Boolean);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const ref = process.argv[2] ?? process.env.GITHUB_HEAD_REF ?? '';
  const tags = repositoryTags();
  const latest = latestVersion(tags);

  if (!latest) throw new Error('No semantic version tag was found.');
  process.stdout.write(`Latest release tag: ${formatVersion(latest)}\n`);

  if (ref) {
    const result = validateCandidate(tags, ref);
    process.stdout.write(`Validated release candidate: ${formatVersion(result.candidate)}\n`);
  } else {
    process.stdout.write(`Next expected patch release: ${formatVersion(nextPatch(latest))}\n`);
  }
}
