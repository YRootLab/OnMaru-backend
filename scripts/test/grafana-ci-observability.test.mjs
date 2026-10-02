import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const dashboardPath = 'observability/grafana/dashboards/ci-benchmark.json';
const evidencePath = 'docs/operations/release-evidence/ci-observability.md';
const read = (path) => readFile(path, 'utf8');
const dashboard = async () => JSON.parse(await read(dashboardPath));

test('CI dashboard imports with the pinned local Mimir and Tempo datasource contract', async () => {
  const data = await dashboard();
  assert.equal(data.uid, 'toolkit-ci-benchmark');
  assert.deepEqual(data.templating.list, []);
  assert.deepEqual(new Set(data.panels.filter((panel) => panel.targets).map((panel) => panel.datasource?.uid)),
    new Set(['local-prometheus', 'local-tempo']));
  for (const panel of data.panels.filter((item) => item.targets)) {
    for (const target of panel.targets) assert.equal(target.datasource?.uid, panel.datasource?.uid);
  }
});

test('CI panels retain bounded success, failure, quality, and export semantics', async () => {
  const data = await dashboard();
  const byTitle = new Map(data.panels.map((panel) => [panel.title, panel]));
  const expressions = (title) => JSON.stringify(byTitle.get(title)?.targets ?? []);
  assert.match(expressions('Workflow observed job window — mean'), /toolkit_ci_workflow_observed_window_seconds_sum/);
  assert.match(expressions('Workflow observed job window — mean'), /toolkit_ci_workflow_observed_window_seconds_count/);
  assert.match(expressions('Outcomes — manifest snapshot, not run totals'), /toolkit_ci_outcome/);
  assert.match(expressions('Collection quality — retained snapshot'), /toolkit_ci_collection_quality/);
  assert.match(expressions('Collector → Tempo failed span attempts'), /otelcol_exporter_send_failed_spans_total/);
  assert.match(expressions('Tempo export queue pressure — lag proxy'), /otelcol_exporter_queue_size/);
  assert.match(expressions('Collector self-scrape health — 1 up \/ 0 down'), /collector:8888/);
  assert.match(expressions('Successful workflow traces — up to 20 matches'), /cicd\.pipeline\.result =/);
  assert.match(expressions('Non-success workflow traces — up to 20 matches'), /cicd\.pipeline\.result !=/);
  for (const panel of data.panels.filter((item) => item.datasource?.type === 'prometheus')) {
    for (const target of panel.targets) {
      assert.doesNotMatch(target.expr, /(?:rate|increase|histogram_quantile)\s*\(\s*toolkit_ci_/);
      assert.doesNotMatch(target.expr, /run_id|attempt|digest|step_name|test_name|path=/);
      if (target.expr.includes('toolkit_ci_')) {
        assert.match(target.expr, /workflow="ci"/);
        assert.match(target.expr, /environment="test"/);
      }
    }
  }
  for (const panel of data.panels.filter((item) => item.datasource?.type === 'tempo')) {
    assert.equal(panel.targets[0].limit, 20);
    assert.match(panel.targets[0].query, /YRootLab\/OnMaru-backend/);
    assert.match(panel.targets[0].query, /\[1-9\]\[0-9\]\{0,18\}/);
  }
});

test('trace links stay on fixed run and local Explore destinations with encoded values', async () => {
  const data = await dashboard();
  for (const panel of data.panels.filter((item) => item.datasource?.type === 'tempo')) {
    const overrides = panel.fieldConfig.overrides;
    const links = overrides.flatMap((override) =>
      override.properties.filter((property) => property.id === 'links')
        .flatMap((property) => property.value.map((link) => ({ field: override.matcher.options, ...link }))));
    assert.deepEqual(new Set(links.map((link) => link.title)), new Set(['Tempo trace', 'Actions run', 'Manifest artifacts']));
    for (const link of links) {
      assert.match(link.url, /\$\{__value\.raw:percentencode\}/);
      if (link.title === 'Tempo trace') {
        assert.equal(link.field, 'traceIdHidden');
        assert.match(link.url, /^\/explore\?/);
        assert.match(decodeURIComponent(link.url), /"datasource":"local-tempo"/);
      } else {
        assert.equal(link.field, 'cicd.pipeline.run.id');
        assert.match(link.url, /^https:\/\/github\.com\/YRootLab\/OnMaru-backend\/actions\/runs\/\$\{__value\.raw:percentencode\}/);
        if (link.title === 'Manifest artifacts') assert.match(link.url, /#artifacts$/);
      }
    }
  }
});

test('evidence template cannot present unexecuted Cloud checks as verified', async () => {
  const source = await read(evidencePath);
  const match = source.match(/```json\n([\s\S]*?)\n```/);
  assert.ok(match, 'machine-readable round-trip record missing');
  const record = JSON.parse(match[1]);
  assert.equal(record.issue, 555);
  assert.deepEqual(Object.keys(record.source).sort(), ['attempt', 'conclusion', 'run_id', 'workflow']);
  assert.equal(record.manifest.digest, null);
  assert.equal(record.export.status, 'pending');
  for (const [name, check] of Object.entries(record.cloud_checks)) {
    assert.equal(check.status, 'pending', `${name} must start pending`);
    assert.equal(check.evidence_url, null);
  }
  assert.ok(Object.keys(record.cloud_checks).length >= 5);
  assert.match(source, /ci-observability-diagnostic-/);
  assert.match(source, /node --test scripts\/test\/grafana-ci-observability\.test\.mjs scripts\/test\/ci-observability-workflow\.test\.mjs/);
  assert.match(source, /--exercise-outage/);
  assert.match(source, /workflow_dispatch/);
  assert.match(source, /2,000/);
  assert.match(source, /1,041/);
  assert.match(source, /100 MiB/);
  assert.match(source, /30일/);
  assert.match(source, /7일/);
});

test('CI credentials remain isolated from runtime secrets and required CI', async () => {
  const [secrets, workflow, ci] = await Promise.all([
    read('docs/operations/runbooks/secrets.md'),
    read('.github/workflows/ci-observability.yml'),
    read('.github/workflows/ci.yml'),
  ]);
  assert.match(secrets, /ci-observability/);
  assert.match(secrets, /OTLP_ENDPOINT/);
  assert.match(secrets, /OTLP_HEADERS/);
  assert.match(secrets, /least.privilege|최소 권한/);
  assert.match(secrets, /rotation|교체/);
  assert.match(workflow, /environment: ci-observability/);
  assert.doesNotMatch(ci, /OTLP_ENDPOINT|OTLP_HEADERS/);
  assert.match(await read('docs/operations/release-evidence/README.md'), /ci-observability\.md/);
});
