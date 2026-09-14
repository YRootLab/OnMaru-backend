# O02 Grafana Cloud dashboard·alert staging checklist

Issue: #81

## Export files

- `observability/grafana/dashboards/onmaru-mvp-operations.json`
- `observability/grafana/alerts/onmaru-alert-rules.json`
- `observability/grafana/notification-policy.json`

## Staging verification checklist

- [ ] Dashboard import succeeds in Grafana Cloud staging stack.
- [ ] Warning route sends test notification to Discord.
- [ ] Critical route sends test notification to Discord and email.
- [ ] Resolve message is emitted after synthetic alert recovery.
- [ ] Dashboard panels include API, sync, AI, SSE, moderation, backup signals.
- [ ] Alert labels and annotations contain no cookie, token, exact location, userId, placeId, runId, raw query, or evidence body.

## Synthetic alert evidence to record

| Scenario | Expected route | Resolve required |
|---|---|---|
| API 5xx warning | Discord | Yes |
| AI running age critical | Discord + email | Yes |
| Backup age critical | Discord + email | Yes |
| Moderation PII critical | Discord + email | Yes |
