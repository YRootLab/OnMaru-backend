#!/bin/sh
# Root-only one-shot W3 operator, bound to the staging compose project and database.
set -eu
if [ "$(id -u)" -ne 0 ] || [ "$#" -lt 1 ]; then
    echo 'Usage: sudo ./selected-operator.sh <run|status|preview|approve|revoke> [--onmaru.discovery.operator.property=value ...]' >&2
    exit 2
fi
ACTION="$1"; shift
case "$ACTION" in run|status|preview|approve|revoke) ;; *) exit 2 ;; esac
for value in "$@"; do
    case "$value" in --onmaru.discovery.operator.action=*|--onmaru.discovery.operator.*=*) ;;
        *) echo 'Only discovery operator properties are accepted.' >&2; exit 2 ;;
    esac
    case "$value" in --onmaru.discovery.operator.action=*) echo 'Action is fixed by the first argument.' >&2; exit 2 ;; esac
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
if [ "$(compose ps spring-api --format '{{.Health}}')" = healthy ]; then
    echo 'Stop staging Spring before a one-shot operator run to respect the 1 GB host.' >&2; exit 1
fi
compose config --quiet
compose up -d postgres
for i in $(seq 1 30); do
    if [ "$(compose ps postgres --format '{{.Health}}')" = healthy ]; then break; fi
    sleep 2
done
if [ "$(compose ps postgres --format '{{.Health}}')" != healthy ]; then
    echo 'Staging PostgreSQL did not become healthy.' >&2; exit 1
fi
trap 'compose stop postgres' EXIT
compose run --rm --no-deps spring-api --spring.main.web-application-type=none "--onmaru.discovery.operator.action=$ACTION" "$@"
