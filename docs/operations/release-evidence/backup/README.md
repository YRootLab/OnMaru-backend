# Backup Restore Evidence

운영 backup의 격리 복원 증적을 기록한다. 절차는 [PostgreSQL restore runbook](../../runbooks/restore.md)의 "Production backup 격리 복원 증적 (Issue #586)" 섹션을 따른다.

## 파일 규약

- `production-restore-<UTC timestamp>.json`: drill summary. `drill: PRODUCTION_BACKUP_ISOLATED_RESTORE`, artifact 이름과 SHA-256, `capturedAt`, RPO/RTO 측정값과 target, integrity 결과, Flyway history count·최근 migration version, PostGIS version, 대표 table row count를 포함한다.
- `restore-*.json`: restore runner(`infra/backup/bin/restore-drill`)의 원본 evidence. summary와 함께 첨부한다.

## 규칙

- secret, connection string, member ID 원문, 서버 경로의 계정 정보를 남기지 않는다. artifact 이름·digest·측정값만 기록한다.
- 증적은 최신 운영 backup 기준이어야 하며, `capturedAt`이 RPO target(24시간)을 넘으면 FAIL이다.
- 결과가 FAIL이면 Issue #586(또는 후속 운영 Issue)에 원인과 대응을 기록한다.
