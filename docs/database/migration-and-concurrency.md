# PostgreSQL migration and concurrency proof plan

DBML is the logical source of schema intent. Flyway migration SQL is the executable source now that the Spring application is scaffolded. PostgreSQL, not an application validator, enforces cross-request invariants. Runtime roles have no DDL privilege; only the migration role can apply schema changes.

## Migration rules

- D01 owns `V001__d01_flyway_migration_baseline.sql`, the `onmaru` and `onmaru_registry` namespaces, NOLOGIN group roles, grants, and `db/migration/registry/migrations.json`.
- D02 owns `V002__d02_catalog_revision_schema.sql`, the catalog canonical place/source/revision tables, catalog enums, provider source uniqueness, and PostGIS geometry/geography indexes.
- D03 owns `V003__d03_identity_session_schema.sql`, provider-independent members, external OAuth identities, opaque session/guest/state hash storage, exploration grants, and deletion ledger tables.
- D07 owns `V004__d07_sync_operations_schema.sql`, sync schedules, runs, leases, checkpoints, watermarks, quarantine rows, and durable outbox events for retryable publication work.
- D09 owns `V005__d09_insights_observations_schema.sql`, visitor observations, tourism targets, target-place links, and concentration observations with provenance, unit, range, and catalog FK constraints.
- D04 owns `V006__d04_journey_discovery_schema.sql`, exploration ownership, async runs, proposals, turns, saved journey snapshots, saved resource rows, and the late identity-to-exploration FKs.
- D05 owns `V007__d05_community_review_schema.sql`, VisitReview rows, member likes, open reports, moderation audit actions, and report self-action guards.
- Environment-specific login roles and passwords are provisioned outside application migration SQL, then granted membership in `onmaru_migration`, `onmaru_runtime`, `onmaru_readonly`, or `onmaru_backup`.
- Flyway uses `baselineOnMigrate=true` with `baselineVersion=0` so an existing pre-Flyway database can still apply `V001`.
- Every migration has a stable `-- onmaru-checksum:` marker and one registry entry. CI runs `scripts/test/migration-policy.test.mjs` to reject duplicate versions and marker drift.
- Use forward-only, reviewed Flyway migrations with `expand -> deploy compatible app -> backfill/verify -> contract` sequencing. Destructive automatic down migrations are prohibited.
- Create `CHECK` constraints for exploration owner XOR and run status/stage/outcome/deadline compatibility. Create partial unique indexes for one active run per exploration and actor key. Create the report uniqueness constraint in DDL.
- Use `NOT VALID` plus later validation only where production-sized existing data requires it; a new schema applies constraints immediately. Every constraint name is stable and mapped to an application error code only after integrity is checked.
- Migration verification runs against a clean PostgreSQL/PostGIS Testcontainers database and against an upgrade fixture from the immediately preceding schema version. Restore compatibility is tested before public writes.

## Transaction rules

Admission and run insertion happen in one short transaction. The lock order is always `global admission -> IP admission -> actor admission -> member -> exploration -> run`. Cancel, completion, expiry sweeper, and deletion use the same order. AI/network calls occur outside DB transactions. Terminal state transitions use generation/status compare-and-set predicates and return active admission capacity in the same transaction.

## Required Testcontainers matrix

| Scenario | Concurrent actors | Required assertion |
|---|---:|---|
| exploration owner insert | two invalid payloads | neither owner and both owners fail DB CHECK |
| run admission | two starts for one exploration | exactly one active run; loser receives stable conflict/rate error |
| actor admission | concurrent different explorations, same actor | exactly one active run for actor |
| cancel vs completion | cancel and valid late FastAPI result | exactly one terminal state; slot returned once |
| deadline sweeper vs completion | sweeper and worker race | expired run cannot become COMPLETED |
| duplicate command | same idempotency key concurrently | one business effect and equivalent cached response |
| member deletion vs completion | deletion begins before late result | result is discarded; no board/save resurrection |
| report creation | duplicate reporter/review submissions | one report record; repeat returns existing state |
| moderation vs public read | HIDDEN/REMOVED transition and list query | public query never returns non-PUBLISHED text |

Every test asserts database rows, public response/error code, and admission counter consistency after commit. Tests use real PostgreSQL locking and partial indexes, not H2 or mocked repositories. Load/restore evidence is a later staging gate, not fabricated by this unit/integration matrix.
