#!/bin/sh
set -eu
if [ "$(id -u)" -ne 0 ]; then
    echo "Run as root via the restricted staging operator command." >&2
    exit 1
fi
DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
docker compose --project-directory "$DIR" --env-file "$DIR/.env" -f "$DIR/compose.yaml" ps --format 'table {{.Service}}\t{{.Status}}'
systemctl list-timers onmaru-staging-auto-stop.timer --no-pager | head -5
