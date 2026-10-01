#!/bin/sh
set -eu
if [ "$(id -u)" -ne 0 ]; then
    echo "Run as root via the restricted staging operator command." >&2
    exit 1
fi
exec 9>/run/onmaru-staging.lock
if ! flock -n 9; then
    echo "Another staging start/stop is in progress; retry shortly." >&2
    exit 1
fi
DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
docker compose --project-directory "$DIR" --env-file "$DIR/.env" -f "$DIR/compose.yaml" stop spring-api postgres
if [ "${1:-}" != --from-timer ]; then
    systemctl stop onmaru-staging-auto-stop.timer || true
fi
printf 'Staging containers stopped; production containers remain running.\n'
