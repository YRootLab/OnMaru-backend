#!/bin/sh
set -eu

LIGHTSAIL_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
COMPOSE_FILE="$LIGHTSAIL_DIR/compose.yaml"
ENV_FILE="$LIGHTSAIL_DIR/.env"

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    echo "Usage: $0 /path/to/source.dump" >&2
    exit 2
fi

DUMP_FILE="$1"
TEMP_CREATE_GRANTED=0
PRE_DATA_SQL=""
RESTORE_TOC=""
compose() {
    docker compose --project-directory "$LIGHTSAIL_DIR" --env-file "$ENV_FILE" \
        -f "$COMPOSE_FILE" "$@"
}

revoke_migration_create() {
    compose exec -T postgres sh -ec 'psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1' <<'SQL'
\getenv migration_user ONMARU_DB_MIGRATION_USER
SELECT format('REVOKE CREATE ON DATABASE %I FROM %I', current_database(), :'migration_user') \gexec
SQL
}

cleanup() {
    STATUS=$?
    trap - EXIT
    if [ -n "$PRE_DATA_SQL" ]; then
        rm -f "$PRE_DATA_SQL"
    fi
    if [ -n "$RESTORE_TOC" ]; then
        rm -f "$RESTORE_TOC"
    fi
    if [ "$TEMP_CREATE_GRANTED" -eq 1 ]; then
        revoke_migration_create || true
    fi
    exit "$STATUS"
}
trap cleanup EXIT

APP_SCHEMAS="$(compose exec -T postgres sh -ec \
    'psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 --tuples-only --no-align' <<'SQL'
SELECT count(*) FROM pg_namespace WHERE nspname IN ('onmaru', 'onmaru_registry');
SQL
)"
if [ "$APP_SCHEMAS" -ne 0 ]; then
    echo "Refusing restore: application schemas already exist. Preserve the volume and plan recovery." >&2
    exit 1
fi

compose exec -T postgres sh -ec 'psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1' <<'SQL'
\getenv migration_user ONMARU_DB_MIGRATION_USER
SELECT format('GRANT CREATE ON DATABASE %I TO %I', current_database(), :'migration_user') \gexec
SQL
TEMP_CREATE_GRANTED=1

PRE_DATA_SQL="$(mktemp)"
chmod 600 "$PRE_DATA_SQL"
compose exec -T postgres sh -ec \
    'pg_restore --section=pre-data --no-owner --no-acl --no-comments --file=-' \
    < "$DUMP_FILE" > "$PRE_DATA_SQL"

# pg_restore clears search_path. The source UUID function calls pgcrypto.digest
# without a schema, so qualify that call before generated columns are created.
python3 - "$PRE_DATA_SQL" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
sql = path.read_text()
source = "SELECT digest(convert_to(value, 'UTF8'), 'md5') AS bytes"
if sql.count(source) != 1:
    raise SystemExit("Expected exactly one java_name_uuid digest call in the source dump")
path.write_text(sql.replace(source, "SELECT public.digest(convert_to(value, 'UTF8'), 'md5') AS bytes"))
PY

compose exec -T postgres sh -ec \
    'psql --username "$ONMARU_DB_MIGRATION_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1' \
    < "$PRE_DATA_SQL"

# PostGIS owns and populates spatial_ref_sys in the target. Exclude only its
# archived table-data entry while keeping every application table-data entry.
RESTORE_TOC="$(mktemp "$LIGHTSAIL_DIR/postgres/restore/.restore-toc.XXXXXX")"
chmod 644 "$RESTORE_TOC"
compose exec -T postgres sh -ec 'pg_restore --list --file=-' < "$DUMP_FILE" > "$RESTORE_TOC"
python3 - "$RESTORE_TOC" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
lines = path.read_text().splitlines(keepends=True)
matches = [i for i, line in enumerate(lines) if " TABLE DATA public spatial_ref_sys " in line and not line.startswith(";")]
if len(matches) != 1:
    raise SystemExit("Expected exactly one PostGIS spatial_ref_sys data entry in the source dump")
lines[matches[0]] = ";" + lines[matches[0]]
path.write_text("".join(lines))
PY

if ! compose exec -T postgres sh -ec \
    'pg_restore --section=data --use-list="/opt/onmaru/restore/$1" --username "$POSTGRES_USER" --role "$ONMARU_DB_MIGRATION_USER" --dbname "$POSTGRES_DB" --no-owner --no-acl --no-comments --exit-on-error' \
    sh "$(basename "$RESTORE_TOC")" < "$DUMP_FILE"; then
    echo "Restore failed in data section. Database may be partially restored; keep the volume and inspect before retrying." >&2
    exit 1
fi

if ! compose exec -T postgres sh -ec \
    'pg_restore --section=post-data --username "$POSTGRES_USER" --role "$ONMARU_DB_MIGRATION_USER" --dbname "$POSTGRES_DB" --no-owner --no-acl --no-comments --exit-on-error' \
    < "$DUMP_FILE"; then
    echo "Restore failed in post-data section. Database may be partially restored; keep the volume and inspect before retrying." >&2
    exit 1
fi

compose exec -T postgres sh -ec \
    'psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 --file /opt/onmaru/restore/grant-application-roles.sql'
revoke_migration_create
TEMP_CREATE_GRANTED=0
rm -f "$PRE_DATA_SQL"
PRE_DATA_SQL=""
rm -f "$RESTORE_TOC"
RESTORE_TOC=""
trap - EXIT

echo "Restore finished. Verify Flyway history, row counts, schema grants, and PostGIS before starting Spring."
