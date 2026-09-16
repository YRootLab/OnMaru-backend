# PostgreSQL Backup And Restore Drill Design

## Goal

최근 PostgreSQL backup을 격리된 새 database에 복원하고, 삭제 ledger를 재적용한 뒤 RPO 24시간, RTO 4시간, 삭제 재노출 0을 기계적으로 검증한다.

## Backup Boundary

backup job은 application runtime credential과 분리된 `onmaru_backup` membership credential을 사용한다. `pg_dump` custom format과 복원 기준 row count, database fingerprint, capture timestamp를 임시 bundle로 만든 뒤 GnuPG AES-256 symmetric encryption을 적용한다. 평문 bundle은 성공과 실패 모두에서 제거하고 encrypted artifact와 checksum만 격리 storage에 남긴다. retention은 성공한 backup 이후 7일보다 오래된 OnMaru artifact만 제거한다.

## Restore Boundary

restore drill은 명시적인 `isolated-target` 확인과 source/target fingerprint 불일치를 모두 요구한다. 새 target에만 `pg_restore --exit-on-error --no-owner --no-privileges`를 실행하고, 외부 encrypted deletion ledger에서 member ID와 요청 시각을 읽어 접근 차단 상태를 재적용한다. replay는 member를 `DELETING`으로 바꾸고 session을 폐기하며 active run, exploration, published review를 비공개 terminal 상태로 전환한다. 물리 cleanup과 장기 ledger lifecycle은 Issue #125의 책임으로 남긴다.

## Integrity And Evidence

integrity suite는 backup 시점 critical table row count, validated foreign keys, 최신 migration reservation, saved snapshot SHA-256 형식, deletion 대상의 active session/run/exploration/public review 노출을 검사한다. 결과와 capture/restore timestamp로 RPO와 RTO를 계산하고 threshold를 넘거나 integrity count가 0이 아니면 drill을 실패시킨다. evidence JSON에는 secret, connection string, member ID를 넣지 않고 recovery mode, source revision, artifact digest, 측정값과 aggregate count만 기록한다.

## PITR Policy

provider PITR은 hosting과 WAL retention이 확정되지 않았으므로 현재 성공 상태로 기록하지 않는다. policy와 runbook은 provider가 target timestamp restore, 별도 credential, isolated target, recovery identifier를 제공하고 동일 integrity suite가 통과한 경우에만 PITR evidence를 허용한다. 그 전까지 자동화된 logical full restore가 RPO/RTO 초안의 실행 기준이며, public write gate에서 PITR 지원을 주장하지 않는다.

## Verification

Node contract test는 backup policy, secret boundary, destructive target guard, replay/integrity query, workflow evidence upload를 고정한다. Docker integration drill은 source와 target PostGIS 17 database를 별도로 띄워 migration과 member-owned fixture를 source에 넣고, encrypted backup을 만든 다음 backup 이후 deletion ledger를 target에 replay하여 삭제 재노출 0과 RPO/RTO 이내 evidence를 검증한다.
