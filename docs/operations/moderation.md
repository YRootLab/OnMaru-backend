# VisitReview moderation operations

VisitReview moderation is the operating process that decides whether a public short review remains visible. It is not automated sentiment scoring and it does not allow an operator to edit the database directly. The public report API and auditable state model are defined in [Public REST API](../contracts/rest-api.md) and `community` DBML.

## State and authority

`PUBLISHED` is visible. `HIDDEN` is temporarily non-public while reviewed. `REMOVED` is a final operator removal. Member deletion uses its separate deletion flow and is not a moderation appeal outcome. Only an internal operator use case changes a moderation state; it creates a `community_review_moderation_actions` record in the same transaction.

Members can report `SPAM`, `ABUSE`, `PERSONAL_DATA`, `COPYRIGHT`, or `OTHER`. A member cannot report their own review and has one open report per review. Report count alone never hides a review. This prevents a coordinated report burst from becoming a takedown mechanism.

## Triage policy

| Class | Initial action | Target | Final choices |
|---|---|---|---|
| High-risk: clear phone/address/account credential, credible threat | narrow high-confidence detector may set `HIDDEN`; create system audit action and critical alert | operator review within 24 hours | restore PUBLISHED, keep HIDDEN, or REMOVED |
| Standard abuse/spam/copyright/other report | remains PUBLISHED while queued | triage within 72 hours | dismiss/keep PUBLISHED, HIDDEN, or REMOVED |
| Automated false positive | no automatic deletion; operator sees detector reason but not hidden model chain-of-thought | same class SLA | restore with audit action |

The operator queue is a protected internal application view backed by report/status/audit records, not a browser-exposed public endpoint and not a separate workflow engine in MVP. It shows review text, report reason/detail, prior actions, detector class, and timestamps. It never exposes reporter identity to the review author. Every disposition requires a reason code; an optional private operator note is access-controlled and excluded from public DTOs and telemetry.

## Grafana and incident handling

Grafana Cloud tracks aggregate open-report age, high-risk hide count, moderation action failures, and queue age. A high-risk temporary hide is `critical` and notifies Discord plus email; normal aging is `warning` and notifies Discord. The alert links this runbook and the protected queue. Alert delivery is a prompt to inspect the queue, not a final judgement.

Before public review write access, the team performs an operator drill: submit each report reason, repeat a report, simulate high-confidence PII, verify immediate non-public rendering, restore one false positive, remove one confirmed violation, verify audit sequence, and verify that public list/cache never returns HIDDEN or REMOVED text. The drill records only synthetic data.

There is no general member appeal workflow in MVP. A contact/appeal channel can be introduced later only with identity, retention, response-time, and operator authority rules; it must not bypass the audit use case.
