# Moderation Queue And Operator Drill Design

## Goal

Issue #139 adds a protected internal queue that lets an authorized operator inspect and disposition VisitReview reports without changing the public database directly. It also records a repeatable synthetic drill proving report handling, high-risk hiding, false-positive restoration, confirmed removal, audit history, and public read exclusion.

## Existing Boundary

Issue #123 already owns report creation, open-report deduplication, review status transitions, and append-only moderation actions. Public VisitReview queries already select only `PUBLISHED` projections. Issue #139 builds an operator view and authorization boundary around those commands; it does not introduce a second workflow engine or a JDBC implementation while the community runtime remains an in-memory scaffold.

## Operator Authentication

Internal moderation requests require both headers:

- `Authorization: Bearer <token>` authenticates access with the `moderation.operator-token` secret.
- `X-OnMaru-Operator: <actor-ref>` identifies the operator recorded in the audit action.

The token is loaded through the existing `SecretProvider`. Current and previous values are accepted during rotation overlap and compared in constant time. A missing bearer token or actor header returns `401 AUTH_REQUIRED`; an invalid token, malformed bearer scheme, or invalid actor reference returns `403 OPERATOR_FORBIDDEN`. Startup remains fail-closed because `moderation.operator-token` is part of the default required secret set.

The existing operator moderation command is moved behind this same authenticator. Possession of an actor header alone no longer grants access.

## Queue Projection

The community module derives the queue from open reports, current review projections, and audit actions. Each queue item contains:

- review ID, review text, and current moderation status;
- report reason, optional detail, and report timestamp without reporter identity;
- prior moderation actions;
- oldest open report time, age in seconds, SLA target, and overdue state;
- `HIGH_RISK` for `PERSONAL_DATA` or a system PII hide, otherwise `STANDARD`.

High-risk items have a 24-hour target and standard items a 72-hour target. Items are ordered by high-risk priority, then oldest open report time, then review ID. The endpoint returns a bounded first 100 items plus generated-at and aggregate oldest-age fields. It always uses `Cache-Control: no-store`.

Reporter member IDs are never part of queue response records. Public report receipts, review DTOs, and HTTP telemetry likewise contain no reporter identity, report detail, review text, operator token, or private note.

## Moderation State Flow

All dispositions reuse `VisitReviewModerationService`:

1. A report remains open while the review is queued.
2. A high-confidence PII detector may call the internal system-hide command. This changes `PUBLISHED` to `HIDDEN` and appends a `SYSTEM / PII_HIGH_RISK` audit action, but keeps reports open for operator review.
3. An operator action changes status and appends an `OPERATOR` audit action.
4. Restoring a false positive uses `PUBLISHED / FALSE_POSITIVE` and dismisses open reports.
5. Confirming a violation uses `HIDDEN` or `REMOVED` and resolves open reports.

Public list and place queries continue filtering on the current status for every response, so no `HIDDEN` or `REMOVED` text can be returned from a stale application cache.

## Synthetic Drill

The Spring integration drill uses only synthetic IDs and text. It performs all five report reasons, repeats one reporter/review pair to prove deduplication, triggers a system PII hide, checks immediate public exclusion, restores that review as a false positive, removes a second confirmed violation, verifies the system/operator audit sequence, and verifies public endpoints never return hidden or removed text.

The drill also exercises missing, invalid, current, and previous operator credentials. An observability assertion verifies HTTP telemetry contains route/status correlation fields only and none of the synthetic reporter IDs, review text, report details, actor reference, or bearer token.

## Documentation

`docs/operations/runbooks/moderation.md` will describe queue access, age/SLA interpretation, alert response, allowed dispositions, the synthetic drill command, expected evidence, and a strict prohibition on direct database updates. `docs/operations/moderation.md` will link to this runbook.

## Verification

- Community domain tests cover queue ordering/SLA/redaction and report resolution transitions.
- Authentication unit tests cover current/previous token overlap, constant-time-backed matching behavior, 401/403 classification, and fail-closed secret configuration.
- Spring boundary tests cover protected queue and protected moderation commands.
- The synthetic drill covers the complete Issue #139 acceptance flow.
- Full Gradle, FastAPI, Node, contract, planning-input, fixture, and diff checks match the repository CI baseline.
