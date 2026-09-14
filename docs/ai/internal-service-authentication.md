# Internal service authentication and failure policy

Spring calls FastAPI only on the private service network over TLS. Browser cookies, member/guest credentials, Tourism API keys, and Gemini keys never cross this boundary. The auth abstraction is `InternalCallerAuthenticator`; the initial implementation is a signed service JWT, replaceable later by workload identity or mTLS without changing the AI proposal/corpus contracts.

## Token policy

| Field | Rule |
|---|---|
| `iss` | environment-specific Spring service issuer |
| `sub` | `spring-api` only |
| `aud` | exact FastAPI service audience |
| `kid` | current signing key identifier |
| `exp` | issued for 60 seconds; FastAPI allows no clock-skew extension beyond 30 seconds |
| `jti` | cryptographically random UUID, one accepted use only |
| correlation | `traceparent`, `requestId`, `runId`, and propagated deadline are separate headers/claims, never member identity |

FastAPI verifies TLS, issuer, audience, signature, expiry, and `jti` **before** body parsing. It records `jti` in a shared TTL replay store using atomic insert-if-absent with TTL `exp + 30 seconds`; an existing value is rejected as `INTERNAL_REPLAY_DETECTED`. The replay store is an operational dependency for AI calls: unavailable means fail closed with `INTERNAL_AUTH_UNAVAILABLE`, never bypassed locally. No token body or signature is logged.

## Key lifecycle and outages

The secret manager stores a current and previous public verification key. Rotation publishes the new key, lets FastAPI verify both keys for at most 10 minutes, then switches Spring signing to the new `kid`; old-key verification is removed after all maximum-lifetime tokens expire. Emergency revocation removes the compromised `kid` from FastAPI's allowed set and disables Spring use immediately, then triggers a critical Grafana alert. Development keys are isolated and cannot verify staging/production tokens.

Spring's bounded FastAPI call has connect/total deadlines from the journey run budget. Authentication, network, or FastAPI 5xx failures do not retry FastAPI: Spring completes the same run with its already validated deterministic candidate board and template evidence reasons under `engine=BASELINE`, when candidates exist. Candidate/intent failure retains its typed clarification or no-results outcome. Grafana correlates the degraded result by traceId/runId and alerts on aggregated failure rate, replay detection, key verification failures, and deadline exhaustion.

## Runbook proof before public AI

1. Valid current and overlapping previous `kid` calls succeed.
2. Wrong issuer/audience, expired token, duplicate `jti`, and unavailable replay store fail closed.
3. Emergency revocation causes a critical alert and no accepted call with the revoked key.
4. FastAPI outage produces one terminal snapshot, preserves the board, and links Spring/FastAPI telemetry through one trace.
5. Test alert and resolve notification arrive in Grafana, Discord, and the critical email route.

A future operations assistant can consume redacted, read-only Grafana aggregates to classify likely provider/auth/corpus incidents and link a runbook. It cannot retrieve secrets, mutate databases, invoke Gemini, rotate keys, acknowledge alerts, or change admission limits.
