import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();

function readJson(path) {
  return JSON.parse(readFileSync(join(root, path), 'utf8'));
}

describe('Grafana observability exports', () => {
  it('publishes required dashboard panels for MVP operations', () => {
    const dashboard = readJson('observability/grafana/dashboards/onmaru-mvp-operations.json');
    const panelTitles = dashboard.panels.map((panel) => panel.title);

    for (const required of [
      'HTTP request/error/latency',
      'JVM and readiness',
      'DB pool wait',
      'AI run stage and terminal',
      'AI provider latency and failure',
      'SSE reset and reconnect',
      'Sync age and failure',
      'Published revision freshness',
      'Corpus manifest ACK lag',
      'Moderation report open age',
      'Backup age',
    ]) {
      assert.ok(panelTitles.includes(required), `${required} panel missing`);
    }
  });

  it('defines warning critical and resolve-capable notification routes', () => {
    const alerts = readJson('observability/grafana/alerts/onmaru-alert-rules.json');
    const severities = new Set(alerts.rules.map((rule) => rule.severity));
    assert.ok(severities.has('warning'));
    assert.ok(severities.has('critical'));

    for (const rule of alerts.rules) {
      assert.ok(rule.annotations.dashboardUrl, `${rule.name} dashboardUrl missing`);
      assert.ok(rule.annotations.runbookUrl, `${rule.name} runbookUrl missing`);
      assert.ok(rule.annotations.environment, `${rule.name} environment annotation missing`);
      assert.ok(rule.noSensitiveLabels, `${rule.name} must disallow sensitive labels`);
    }

    const notification = readJson('observability/grafana/notification-policy.json');
    assert.equal(notification.resolveMessagesEnabled, true);
    assert.deepEqual(notification.routes.warning.contactPoints, ['discord']);
    assert.deepEqual(notification.routes.critical.contactPoints, ['discord', 'email']);
  });
});
