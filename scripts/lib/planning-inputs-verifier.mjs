import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { relative, resolve, sep } from 'node:path';

const manifestPath = 'docs/reference-snapshots/planning-inputs/manifest.json';
const requiredEntryFields = [
  'snapshot_path',
  'source_repository',
  'source_commit',
  'source_path',
  'sha256',
];
const scanRoots = [
  'docs/reference-snapshots/planning-inputs',
  'docs/contracts/fixtures',
];
const secretPatterns = [
  /\b(?:api[_-]?key|access[_-]?token|secret|password)\b["'\s:=]+["']?[^"'\s,}]{8,}/i,
  /\bsk-[A-Za-z0-9_-]{8,}\b/,
  /-----BEGIN (?:RSA |EC |OPENSSH |PRIVATE )?PRIVATE KEY-----/,
];

export function sha256Hex(content) {
  return createHash('sha256').update(content).digest('hex');
}

function isInside(root, candidate) {
  const path = resolve(root, candidate);
  const rel = relative(root, path);
  return rel && !rel.startsWith('..') && !rel.includes(`..${sep}`);
}

function walkFiles(root, relativeRoot) {
  const absoluteRoot = resolve(root, relativeRoot);
  if (!existsSync(absoluteRoot)) return [];
  const files = [];
  const visit = (absolutePath) => {
    const stats = statSync(absolutePath);
    if (stats.isDirectory()) {
      for (const entry of readdirSync(absolutePath)) visit(resolve(absolutePath, entry));
      return;
    }
    if (stats.isFile()) files.push(relative(root, absolutePath));
  };
  visit(absoluteRoot);
  return files.sort();
}

function validateEntry(root, entry, index) {
  const errors = [];
  for (const field of requiredEntryFields) {
    if (!entry[field]) errors.push(`manifest entry ${index} is missing ${field}`);
  }
  if (!entry.snapshot_path) return errors;
  if (!entry.snapshot_path.startsWith('docs/reference-snapshots/planning-inputs/')) {
    errors.push(`${entry.snapshot_path} must be inside docs/reference-snapshots/planning-inputs`);
  }
  if (!isInside(root, entry.snapshot_path)) {
    errors.push(`${entry.snapshot_path} escapes repository root`);
    return errors;
  }
  const absoluteSnapshot = resolve(root, entry.snapshot_path);
  if (!existsSync(absoluteSnapshot)) {
    errors.push(`${entry.snapshot_path} is missing`);
    return errors;
  }
  const actual = sha256Hex(readFileSync(absoluteSnapshot));
  if (entry.sha256 && actual !== entry.sha256) {
    errors.push(`${entry.snapshot_path} sha256 mismatch: expected ${entry.sha256}, got ${actual}`);
  }
  return errors;
}

function validateSecretScan(root) {
  const errors = [];
  for (const scanRoot of scanRoots) {
    for (const file of walkFiles(root, scanRoot)) {
      if (file.endsWith('/manifest.json')) continue;
      const content = readFileSync(resolve(root, file), 'utf8');
      if (secretPatterns.some((pattern) => pattern.test(content))) {
        errors.push(`${file} contains a secret-like value`);
      }
    }
  }
  return errors;
}

function validateManifestCoverage(root, entries) {
  const errors = [];
  const listed = new Set(entries.map((entry) => entry.snapshot_path).filter(Boolean));
  for (const file of walkFiles(root, 'docs/reference-snapshots/planning-inputs')) {
    if (file.endsWith('/manifest.json')) continue;
    if (!listed.has(file)) errors.push(`${file} is not listed in manifest`);
  }
  return errors;
}

export function verifyPlanningInputs(root = process.cwd()) {
  const errors = [];
  const absoluteManifest = resolve(root, manifestPath);
  if (!existsSync(absoluteManifest)) {
    return { ok: false, errors: [`${manifestPath} is missing`] };
  }

  let manifest;
  try {
    manifest = JSON.parse(readFileSync(absoluteManifest, 'utf8'));
  } catch (error) {
    return { ok: false, errors: [`${manifestPath} is not valid JSON: ${error.message}`] };
  }

  if (manifest.version !== 1) errors.push('manifest version must be 1');
  if (!Array.isArray(manifest.entries) || manifest.entries.length === 0) {
    errors.push('manifest entries must be a non-empty array');
  } else {
    for (const [index, entry] of manifest.entries.entries()) {
      errors.push(...validateEntry(root, entry, index));
    }
    errors.push(...validateManifestCoverage(root, manifest.entries));
  }

  errors.push(...validateSecretScan(root));
  return { ok: errors.length === 0, errors };
}
