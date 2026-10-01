#!/bin/sh
set -eu

LIGHTSAIL_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
BACKUP_DIR="${ONMARU_BACKUP_DIR:-$LIGHTSAIL_DIR/backups}"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_FILE="$BACKUP_DIR/onmaru-$TIMESTAMP.dump"

docker compose --project-directory "$LIGHTSAIL_DIR" --env-file "$LIGHTSAIL_DIR/.env" \
  -f "$LIGHTSAIL_DIR/compose.yaml" exec -T postgres sh -ec \
  'PGPASSWORD="$ONMARU_DB_BACKUP_PASSWORD" pg_dump --host 127.0.0.1 --username "$ONMARU_DB_BACKUP_USER" --dbname "$POSTGRES_DB" --format=custom --no-owner --no-acl' \
  > "$BACKUP_FILE"

test -s "$BACKUP_FILE"
chmod 600 "$BACKUP_FILE"
sha256sum "$BACKUP_FILE"
printf 'Created backup: %s\n' "$BACKUP_FILE"
