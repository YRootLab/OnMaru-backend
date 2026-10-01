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

if ! compose exec -T postgres sh -ec \
    'pg_restore --username "$POSTGRES_USER" --role "$ONMARU_DB_MIGRATION_USER" --dbname "$POSTGRES_DB" --no-owner --no-acl --exit-on-error -' \
    < "$DUMP_FILE"; then
    echo "Restore failed. Database schema may be partially restored; keep the volume and inspect before retrying." >&2
    exit 1
fi

compose exec -T postgres sh -ec \
    'psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 --file /opt/onmaru/restore/grant-application-roles.sql'
revoke_migration_create
TEMP_CREATE_GRANTED=0
trap - EXIT

echo "Restore finished. Verify Flyway history, row counts, schema grants, and PostGIS before starting Spring."
