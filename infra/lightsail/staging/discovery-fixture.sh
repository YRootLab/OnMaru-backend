#!/bin/sh
# Root-only synthetic cursor/rollback rehearsal on staging PostgreSQL.
set -eu
if [ "$(id -u)" -ne 0 ] || [ "$#" -ne 1 ]; then
    echo 'Usage: sudo ./discovery-fixture.sh install|remove' >&2; exit 2
fi
case "$1" in
    install) FILE=staging-discovery-fixture.sql ;;
    remove) FILE=remove-staging-discovery-fixture.sql ;;
    *) exit 2 ;;
esac
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
    echo 'Start staging PostgreSQL first.' >&2; exit 1
fi
compose exec -T postgres sh -ec 'exec psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -X -v ON_ERROR_STOP=1 -f -' < "$DIR/../../../testing/kcontents-pilot/$FILE"
