import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const TAG_PATTERN = /^v\d+\.\d+\.\d+$/;
const SHA_PATTERN = /^[a-f0-9]{40}$/;
const DIGEST_PATTERN = /^sha256:[a-f0-9]{64}$/;
const HASH_PATTERN = /^[a-f0-9]{64}$/;
const EMAIL_PATTERN = /\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/i;
const CREDENTIAL_URL_PATTERN = /\b[a-z][a-z\d+.-]*:\/\/[^/\s:@]+:[^/\s@]+@/i;
const SECRET_KEY_PATTERN = /(?:secret|password|passwd|token|api[_-]?key|authorization|cookie|credential)/i;
const PII_KEY_PATTERN = /(?:email|phone|mobile|user(?:name|id)?|customer|account|address|ssn|resident)/i;
const SENSITIVE_ASSIGNMENT_PATTERN = /\b(?:secret|password|passwd|token|api[_-]?key|authorization|cookie|credential)\s*[:=]\s*[^\s,;}]+/gi;

export const ALLOWED_STATUSES = Object.freeze(['improved', 'unchanged', 'regressed', 'inconclusive']);

function fail(message) {
  throw new Error(message);
}

function assertString(value, field) {
  if (typeof value !== 'string' || value.length === 0) fail(`${field} must be a non-empty string`);
}

function assertSafeString(value, field) {
  if (EMAIL_PATTERN.test(value) || CREDENTIAL_URL_PATTERN.test(value)) {
    fail(`${field} contains sensitive or PII data`);
  }
  if (SENSITIVE_ASSIGNMENT_PATTERN.test(value)) {
    SENSITIVE_ASSIGNMENT_PATTERN.lastIndex = 0;
    fail(`${field} contains sensitive data`);
  }
  SENSITIVE_ASSIGNMENT_PATTERN.lastIndex = 0;
}

function assertSafeTree(value, path = 'input') {
  if (typeof value === 'string') {
    assertSafeString(value, path);
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) => assertSafeTree(item, `${path}[${index}]`));
    return;
  }
  if (value && typeof value === 'object') {
    for (const [key, child] of Object.entries(value)) {
      if (SECRET_KEY_PATTERN.test(key) || PII_KEY_PATTERN.test(key)) {
        fail(`${path}.${key} contains sensitive or PII data`);
      }
      assertSafeTree(child, `${path}.${key}`);
    }
  }
}

function assertRelativeArtifactPath(value, field) {
  assertString(value, field);
  if (value.startsWith('/') || value.split('/').includes('..')) fail(`${field} contains an unsafe path`);
  if (CREDENTIAL_URL_PATTERN.test(value)) fail(`${field} contains a credential URL`);
  assertSafeString(value, field);
}

function assertDigest(value, field) {
  if (typeof value !== 'string' || !DIGEST_PATTERN.test(value)) fail(`${field} must match sha256:<64 lowercase hex>`);
}

function assertSha(value, field = 'commitSha') {
  if (typeof value !== 'string' || !SHA_PATTERN.test(value)) fail(`${field} must be a 40-character lowercase commit SHA`);
}

function assertTag(value, field = 'tag') {
  if (typeof value !== 'string' || !TAG_PATTERN.test(value)) fail(`${field} must match vX.Y.Z`);
}

function assertImageRepository(value, field) {
  assertString(value, field);
  if (value.includes('@') || value.includes('?') || value.includes('#') || CREDENTIAL_URL_PATTERN.test(value)) {
    fail(`${field} must be a credential-free image repository`);
  }
  assertSafeString(value, field);
}

function validateService(service, index) {
  const field = `services[${index}]`;
  if (!service || typeof service !== 'object') fail(`${field} must be an object`);
  assertString(service.name, `${field}.name`);
  assertImageRepository(service.image, `${field}.image`);
  assertSha(service.imageTag, `${field}.imageTag`);
  assertDigest(service.expectedDigest, `${field}.expectedDigest`);
  assertDigest(service.deployedDigest, `${field}.deployedDigest`);
  assertString(service.readinessPath, `${field}.readinessPath`);
  if (!service.readinessPath.startsWith('/') || service.readinessPath.includes('..')) {
    fail(`${field}.readinessPath must be a safe absolute path`);
  }
  if (service.expectedDigest !== service.deployedDigest) {
    fail(`deployedDigest mismatch for ${service.name}: expected ${service.expectedDigest}, got ${service.deployedDigest}`);
  }
}

export function validateReleaseMetadata(metadata) {
  if (!metadata || typeof metadata !== 'object') fail('release metadata must be an object');
  assertSafeTree(metadata);
  if (metadata.schemaVersion !== 1) fail('schemaVersion must be 1');
  assertTag(metadata.releaseId, 'releaseId');
  assertTag(metadata.tag);
  if (metadata.releaseId !== metadata.tag) fail('releaseId must equal tag');
  assertSha(metadata.commitSha);
  if (metadata.environment !== 'staging') fail('environment must be staging');
  assertString(metadata.workflowRunId, 'workflowRunId');
  if (!Array.isArray(metadata.services) || metadata.services.length === 0) fail('services must not be empty');
  const names = new Set();
  metadata.services.forEach((service, index) => {
    validateService(service, index);
    if (names.has(service.name)) fail(`duplicate service name: ${service.name}`);
    names.add(service.name);
  });
  return metadata;
}

export function createReleaseMetadata(input) {
  const metadata = { schemaVersion: 1, ...input };
  return validateReleaseMetadata(metadata);
}

export function hashConfig(config) {
  const bytes = typeof config === 'string' || Buffer.isBuffer(config) ? config : JSON.stringify(config);
  if (bytes === undefined) fail('config must be serializable');
  return createHash('sha256').update(bytes).digest('hex');
}

function assertUri(value, field) {
  assertString(value, field);
  if (value.startsWith('http://') || value.startsWith('https://')) {
    if (CREDENTIAL_URL_PATTERN.test(value)) fail(`${field} contains a credential URL`);
  } else {
    assertRelativeArtifactPath(value, field);
  }
}

export function validateBenchmarkManifest(manifest) {
  if (!manifest || typeof manifest !== 'object') fail('benchmark manifest must be an object');
  assertSafeTree(manifest);
  if (manifest.schemaVersion !== 1) fail('schemaVersion must be 1');
  assertString(manifest.benchmarkRunId, 'benchmarkRunId');
  assertTag(manifest.releaseId, 'releaseId');
  assertTag(manifest.baselineReleaseId, 'baselineReleaseId');
  assertTag(manifest.candidateReleaseId, 'candidateReleaseId');
  assertSha(manifest.commitSha);
  if (!manifest.imageDigests || typeof manifest.imageDigests !== 'object') fail('imageDigests must be an object');
  for (const [service, digest] of Object.entries(manifest.imageDigests)) assertDigest(digest, `imageDigests.${service}`);
  if (manifest.environment !== 'staging') fail('environment must be staging');
  assertString(manifest.suite, 'suite');
  if (!manifest.toolkit || manifest.toolkit.name !== 'pipeline-toolkit') fail('toolkit.name must be pipeline-toolkit');
  assertString(manifest.toolkit.version, 'toolkit.version');
  if (manifest.toolkit.version !== 'unverified' && /[\r\n]/.test(manifest.toolkit.version)) fail('toolkit.version is invalid');
  if (typeof manifest.configSha256 !== 'string' || !HASH_PATTERN.test(manifest.configSha256)) fail('configSha256 must be a 64-character lowercase SHA-256');
  if (!ALLOWED_STATUSES.includes(manifest.status)) fail(`status must be one of ${ALLOWED_STATUSES.join(', ')}`);
  assertUri(manifest.rawUri, 'rawUri');
  assertUri(manifest.normalizedUri, 'normalizedUri');
  assertUri(manifest.reportUri, 'reportUri');
  return manifest;
}

export function createBenchmarkManifest(input) {
  assertSafeTree(input);
  const configSha256 = input.config === undefined ? input.configSha256 : hashConfig(input.config);
  if (input.config !== undefined && input.configSha256 !== undefined && configSha256 !== input.configSha256) {
    fail('configSha256 does not match config');
  }
  const manifest = {
    schemaVersion: 1,
    ...input,
    configSha256,
  };
  delete manifest.config;
  return validateBenchmarkManifest(manifest);
}

export function redactSensitive(value) {
  if (typeof value === 'string') {
    return value
      .replace(CREDENTIAL_URL_PATTERN, '[REDACTED]')
      .replace(EMAIL_PATTERN, '[REDACTED]')
      .replace(SENSITIVE_ASSIGNMENT_PATTERN, (match) => match.replace(/([:=])\s*[^\s,;}]+$/, '$1[REDACTED]'));
  }
  if (Array.isArray(value)) return value.map(redactSensitive);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, child]) => [
      key,
      SECRET_KEY_PATTERN.test(key) || PII_KEY_PATTERN.test(key) ? '[REDACTED]' : redactSensitive(child),
    ]));
  }
  return value;
}

function argumentValue(args, name) {
  const index = args.indexOf(name);
  if (index === -1 || !args[index + 1]) fail(`missing ${name}`);
  return args[index + 1];
}

function runCli() {
  const [command, ...args] = process.argv.slice(2);
  if (!['release', 'manifest'].includes(command)) fail('usage: release-metadata.mjs <release|manifest> --input <json> --output <json>');
  const input = JSON.parse(readFileSync(argumentValue(args, '--input'), 'utf8'));
  const output = command === 'release' ? createReleaseMetadata(input) : createBenchmarkManifest(input);
  writeFileSync(argumentValue(args, '--output'), `${JSON.stringify(output, null, 2)}\n`, 'utf8');
}

if (import.meta.url === `file://${process.argv[1]}`) {
  try {
    runCli();
  } catch (error) {
    console.error(JSON.stringify({ error: redactSensitive(error instanceof Error ? error.message : String(error)) }));
    process.exitCode = 1;
  }
}
