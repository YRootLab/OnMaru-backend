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

export function validateCandidate(tags, ref) {
  const latest = latestVersion(tags);
  if (!latest) throw new Error('No semantic version tag was found. A release must start from an approved baseline.');

  const candidate = candidateVersion(ref);
  if (!candidate) throw new Error(`Invalid release ref: ${ref}. Expected release/vX.Y.Z.`);

  const expected = nextPatch(latest);
  if (compareVersions(candidate, expected) !== 0) {
    throw new Error(
      `Release version ${formatVersion(candidate)} is not the next patch after ${formatVersion(latest)}. `
        + `Expected ${formatVersion(expected)}.`,
    );
  }

  return { latest, expected, candidate };
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
