# PostgreSQL Backup And Restore Drill Runbook

이 runbook은 Issue #94의 암호화 logical backup과 격리 복원 절차를 고정한다. 목표는 **RPO 24시간**, **RTO 4시간**, deletion ledger 재적용 뒤 삭제 재노출 0이다. 실제 secret, connection string, member ID는 evidence, log, Issue, PR에 기록하지 않는다.

## 운영 경계

- backup login은 `onmaru_backup` NOLOGIN group role만 상속하며 runtime login과 공유하지 않는다.
- backup artifact와 deletion ledger artifact는 application database와 다른 encrypted storage에 7일 보관한다.
- backup encryption key와 deletion ledger encryption key는 secret manager에서 별도로 mount한다.
- restore owner는 일회성 target에만 사용하고 application runtime이나 source database에는 연결하지 않는다.
- 자동화는 `ONMARU_RESTORE_CONFIRM=isolated-target`과 source/target fingerprint 불일치를 모두 확인한다.
- backup dump와 critical row count는 하나의 exported MVCC snapshot을 공유한다.
- checksum sidecar는 digest뿐 아니라 정확한 artifact basename까지 일치해야 한다.

`infra/backup/policy.json`이 schedule, retention, credential, RPO/RTO의 machine-readable source다. runner image는 PostgreSQL 17 client, GnuPG, jq를 고정한다. 통합 drill의 PostGIS database image는 현재 amd64만 제공되므로 ARM 개발 환경에서는 기본 `linux/amd64` emulation을 사용하며, 필요하면 `ONMARU_POSTGIS_PLATFORM`으로 명시한다.

## Backup

먼저 runner image를 만든다.

```bash
docker build -t onmaru-backup:local infra/backup
```

backup job에는 `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`를 secret manager에서 주입하고, `PGUSER`는 `onmaru_backup` membership을 가진 non-superuser login으로 제한한다. runner도 시작 시 이 membership과 superuser 여부를 검사한다. 다음 path는 모두 container 내부 mount다.

```bash
docker run --rm \
  -e PGHOST -e PGPORT -e PGDATABASE -e PGUSER -e PGPASSWORD \
  -e ONMARU_BACKUP_OUTPUT_DIR=/backup \
  -e ONMARU_BACKUP_KEY_FILE=/run/secrets/backup-key \
  -e ONMARU_SOURCE_REVISION \
  -v "$BACKUP_STORAGE:/backup" \
  -v "$BACKUP_KEY_FILE:/run/secrets/backup-key:ro" \
  onmaru-backup:local create-backup
```

성공 시 storage에는 `onmaru-*.tar.gpg`와 동일 이름의 `.sha256`만 남아야 한다. 평문 `pg_dump`, metadata, row count 파일이 있으면 실패로 보고 storage를 격리한다. dump와 critical count는 backup runner가 유지하는 read-only exported snapshot에서 함께 읽으므로 운영 write가 계속되어도 서로 다른 시점의 count를 비교하지 않는다. 7일을 지난 OnMaru artifact는 새 backup 성공 뒤 정리된다.

## Deletion Ledger Input

복원 시점 ledger는 backup 생성 이후 접수된 삭제를 포함해야 한다. 원본 TSV 한 행은 `<member UUID><TAB><requested_at ISO-8601>`이며 header는 없다. ledger producer와 장기 물리 cleanup은 Issue #125가 소유한다. 이 runbook은 별도 key로 암호화된 `.gpg` artifact와 `.sha256`만 입력으로 허용하고, 평문은 restore runner 임시 디렉터리 밖에 두지 않는다.

## Isolated Restore

1. public write와 background writer를 중지한다.
2. source와 다른 host 또는 cluster에 새 빈 database를 만든다. system·extension-owned object 외 사용자 relation이 하나라도 있으면 restore runner가 거부한다.
3. 최신 encrypted backup, checksum, backup key, encrypted deletion ledger, ledger key를 read-only로 mount한다.
4. 아래 명령으로 schema/data restore, deletion ledger replay, integrity query를 실행한다.
5. evidence가 `PASS`이고 RPO/RTO와 모든 integrity count가 기준 안인지 확인한다.
6. 호환 application을 target에 연결해 readiness와 private/public negative test를 수행한다.
7. 실제 incident가 아니라 drill이면 target과 일회성 credential을 폐기한다.

```bash
docker run --rm \
  -e PGHOST -e PGPORT -e PGDATABASE -e PGUSER -e PGPASSWORD \
  -e ONMARU_BACKUP_ARTIFACT=/backup/onmaru-latest.tar.gpg \
  -e ONMARU_BACKUP_KEY_FILE=/run/secrets/backup-key \
  -e ONMARU_DELETION_LEDGER_ARTIFACT=/ledger/deletions.tsv.gpg \
  -e ONMARU_DELETION_LEDGER_KEY_FILE=/run/secrets/ledger-key \
  -e ONMARU_RESTORE_EVIDENCE_DIR=/evidence \
  -e ONMARU_RESTORE_CONFIRM=isolated-target \
  -v "$BACKUP_STORAGE:/backup:ro" \
  -v "$LEDGER_STORAGE:/ledger:ro" \
  -v "$BACKUP_KEY_FILE:/run/secrets/backup-key:ro" \
  -v "$LEDGER_KEY_FILE:/run/secrets/ledger-key:ro" \
  -v "$EVIDENCE_DIR:/evidence" \
  onmaru-backup:local restore-drill
```

`integrity.countMismatches`, `unvalidatedForeignKeys`, `invalidSavedSnapshotHashes`, `deletionReexposureCount`가 모두 0이어야 한다. deletion replay는 member를 `DELETING`으로 잠그고 session 폐기, active run 취소, exploration soft-delete, published review 숨김을 한 transaction에서 적용한다.

## Local And CI Drill

두 개의 실제 PostGIS container로 전체 흐름을 재현한다.

```bash
infra/backup/test/run-restore-drill
```

GitHub `PostgreSQL Restore Drill` workflow는 관련 파일이 바뀐 PR과 수동 실행에서 이 명령을 수행하고 sanitized evidence JSON을 7일 artifact로 보존한다. drill은 정상 복원 전에 다른 artifact명을 적은 checksum sidecar와 `public` 사용자 table이 있는 target이 각각 거부되는지도 확인한다. synthetic key와 member는 일회성 container와 temporary directory 안에서만 사용한다.

## PITR Gate

현재 PITR 상태는 `UNAVAILABLE_UNTIL_HOSTING_SELECTED`다. hosting provider, WAL archive/retention, recovery target timestamp, provider recovery ID가 확정되지 않은 상태에서 PITR 성공을 주장하지 않는다. provider PITR을 활성화할 때도 source와 분리된 target에 복구한 뒤 이 runbook의 deletion ledger와 integrity suite를 동일하게 실행하고, target timestamp와 recovery ID를 evidence에 추가해야 한다. 이 gate가 없으면 public write 인수에서 PITR을 지원으로 표시하지 않는다.
