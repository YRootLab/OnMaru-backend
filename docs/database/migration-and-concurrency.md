# PostgreSQL migration and concurrency proof plan

DBML is the logical source of schema intent. Flyway migration SQL is the executable source once the Spring application is scaffolded. PostgreSQL, not an application validator, enforces cross-request invariants. Runtime roles have no DDL privilege; only the migration role can apply schema changes.

## Migration rules

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
