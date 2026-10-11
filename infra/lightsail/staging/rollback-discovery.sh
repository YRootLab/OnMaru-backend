#!/bin/sh
# Root-only staging selected-discovery pointer rollback.
set -eu
if [ "$(id -u)" -ne 0 ] || [ "$#" -ne 2 ]; then
    echo 'Usage: sudo ./rollback-discovery.sh <expected-active-uuid> <target-published-uuid>' >&2
    exit 2
fi
for value in "$1" "$2"; do
    if ! printf '%s' "$value" | grep -Eq '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'; then
        echo 'Expected UUID arguments.' >&2; exit 2
    fi
done
DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ "$DIR" != /opt/onmaru/staging-repo/infra/lightsail/staging ]; then
    echo 'Use the staging checkout only.' >&2; exit 1
fi
ENV="$DIR/.env"
if [ ! -f "$ENV" ] || [ "$(stat -c %a "$ENV")" != 600 ] || ! grep -qx 'ONMARU_POSTGRES_DB=onmaru_staging' "$ENV"; then
    echo 'Staging environment safety check failed.' >&2; exit 1
fi
exec 9>/run/onmaru-staging.lock
flock -n 9 || { echo 'Staging operation in progress.' >&2; exit 1; }
compose() { docker compose --project-directory "$DIR" --env-file "$ENV" -f "$DIR/compose.yaml" "$@"; }
if [ "$(compose ps postgres --format '{{.Health}}')" != healthy ]; then
    echo 'Staging PostgreSQL is not healthy.' >&2; exit 1
fi
SQL="$DIR/../../../testing/kcontents-pilot/rollback-discovery.sql"
compose exec -T postgres sh -ec 'exec psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -X -v ON_ERROR_STOP=1 -v expected_active="$1" -v target_revision="$2" -f -' sh "$1" "$2" < "$SQL"
