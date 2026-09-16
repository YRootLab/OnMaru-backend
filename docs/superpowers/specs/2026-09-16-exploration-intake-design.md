# Issue 101 Exploration Intake Design

## Goal

Issue #101 implements the first usable journey exploration intake surface: public/member exploration creation, snapshot lookup, and turn intake ownership checks.

## Scope

- Implement `POST /api/v1/explorations`.
- Implement `GET /api/v1/explorations/{explorationId}`.
- Implement `POST /api/v1/explorations/{explorationId}/turns`.
- Preserve guest/member ownership and conceal other actors as 404.
- Reject invalid input without creating exploration rows or raw turn records.
- Return clarification for missing region without calling an AI provider.

## Out Of Scope

- Durable worker execution, SSE streaming, and run idempotency state machine remain Issue #109.
- PostgreSQL repository implementation is deferred; this branch follows the existing scaffold pattern with an in-memory store.
- AI provider execution is represented by an explicit port and a counting fake in tests.

## Architecture

The journey module owns exploration state and business rules. The Spring API module owns actor resolution from cookies, HTTP request validation, no-store responses, CSRF enforcement through existing filters, and error envelope mapping.

The domain service exposes create, get, and turn methods that receive an `ExplorationActor`. Ownership is checked in the service so the same rules apply across web and future persistence adapters. A missing region returns a completed baseline run with `CLARIFICATION_REQUIRED`; the AI port is not called on that path.

`GET /auth/csrf` bootstraps a server-registered 24-hour guest credential. Exploration endpoints never trust a caller-invented cookie, and credential absence returns 401. After login, direct member ownership or the existing scoped guest grant grants access; every other actor receives 404.

Safety, privacy, and journey-scope intake policy runs before raw input persistence. Accepted POST commands use the shared idempotency store, keyed by actor, operation, path, and request fingerprint. Same-key replay returns the original `RunAccepted`; a different payload returns `IDEMPOTENCY_CONFLICT`.

## Testing

Service tests cover state rules: guest/member ownership, other actor concealment, turn persistence, validation/policy rejection, and no AI call for missing region. Web boundary tests cover server-issued guest credentials, member guest grants, idempotency replay/conflict, required baseVersion, the public API contract, status codes, no-store headers, and request bodies shaped by the OpenAPI contract.
