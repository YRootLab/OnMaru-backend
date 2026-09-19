import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import test from 'node:test';

const manifestPath = new URL('../../docs/reference-snapshots/tourapi/manifest.json', import.meta.url);
const fixtureBase = new URL('../../testing/fixtures/provider/tourapi/', import.meta.url);

const requiredCases = new Set([
  'normal-list',
  'location-list',
  'detail-common',
  'empty-list',
  'last-page',
  'http-200-error-envelope',
  'http-4xx',
  'http-5xx',
  'rate-limited-429',
]);

async function readJson(url) {
  return JSON.parse(await readFile(url, 'utf8'));
}

function assertNoSecretMaterial(value, path = '$') {
  if (typeof value === 'string') {
    assert.doesNotMatch(value, /serviceKey|[?&]key=|[?&]auth|encodingKey|decodingKey/i, path);
    assert.doesNotMatch(value, /[A-Za-z0-9+/]{60,}={0,2}/, path);
    return;
  }

  if (Array.isArray(value)) {
    value.forEach((item, index) => assertNoSecretMaterial(item, `${path}[${index}]`));
    return;
  }

  if (value && typeof value === 'object') {
    for (const [key, child] of Object.entries(value)) {
      assertNoSecretMaterial(child, `${path}.${key}`);
    }
  }
}

test('TourAPI qualification manifest covers every issue #66 acceptance fixture', async () => {
  const manifest = await readJson(manifestPath);

  assert.equal(manifest.provider, '한국관광공사 국문 관광정보 서비스_GW');
  assert.equal(manifest.issue, 66);
  assert.equal(manifest.secretPolicy.serviceKeyStored, false);
  assert.equal(manifest.qualification.liveCanonicalBlocked, true);

  const caseIds = new Set(manifest.cases.map((entry) => entry.id));
  assert.deepEqual(caseIds, requiredCases);
  assert.deepEqual(
    new Set(manifest.qualification.operationCoverage),
    new Set(['areaBasedList2', 'locationBasedList2', 'detailCommon2', 'searchKeyword2', 'areaCode2']),
  );
  assert.ok(manifest.quota.dailyDevelopmentLimit >= 1000);
  assert.ok(manifest.license.publicUseAllowed);
  assert.match(manifest.license.attribution, /한국관광공사/);

  assertNoSecretMaterial(manifest);
});

test('TourAPI provider fixtures are redacted and retain parse-critical envelope fields', async () => {
  const manifest = await readJson(manifestPath);
  const manifestFixtures = new Set(manifest.cases.map((entry) => entry.fixture));
  const fixtureFiles = new Set((await readdir(fixtureBase)).filter((file) => file.endsWith('.json')));

  assert.deepEqual(fixtureFiles, manifestFixtures);

  for (const entry of manifest.cases) {
    const fixture = await readJson(new URL(entry.fixture, fixtureBase));

    assert.equal(fixture.caseId, entry.id);
    assert.equal(fixture.redacted.serviceKey, true);
    assert.equal(fixture.request.serviceKey, '<REDACTED>');
    assert.equal(fixture.provider, 'kto-tourapi-korean');
    assert.ok(Number.isInteger(fixture.observed.httpStatus));

    if (entry.id.startsWith('http-') || entry.id === 'rate-limited-429') {
      assert.ok(fixture.expected.classification);
    } else {
      assert.equal(fixture.body.response.header.resultCode, '0000');
      assert.ok(Number.isInteger(fixture.body.response.body.pageNo));
      assert.ok(Number.isInteger(fixture.body.response.body.numOfRows));
      assert.ok(Number.isInteger(fixture.body.response.body.totalCount));
    }

    assertNoSecretMaterial(fixture);
  }
});
