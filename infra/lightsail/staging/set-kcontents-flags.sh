#!/bin/sh
# Root-only staging switch. Never expose through the FE operator's forced command.
set -eu
if [ "$(id -u)" -ne 0 ] || [ "$#" -ne 2 ]; then
    echo 'Usage: sudo ./set-kcontents-flags.sh <discovery:on|off> <research:on|off>' >&2
    exit 2
fi
case "$1:$2" in on:on|on:off|off:on|off:off) ;; *) exit 2 ;; esac
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
if [ "$(docker compose --project-directory "$DIR" --env-file "$ENV" -f "$DIR/compose.yaml" ps spring-api --format '{{.Health}}')" != healthy ]; then
    echo 'Start staging before changing flags.' >&2; exit 1
fi
if [ "$2" = on ] && ! grep -Eq '^ONMARU_KCONTENTS_RESEARCH_WORKER_TOKEN=.+$' "$ENV"; then
    echo 'Research requires a non-empty staging worker token.' >&2; exit 1
fi
BACKUP="$(mktemp "$DIR/.env.rollback.XXXXXX")"
chmod 600 "$BACKUP"
cp "$ENV" "$BACKUP"
restore() { cp "$BACKUP" "$ENV"; chmod 600 "$ENV"; rm -f "$BACKUP"; }
trap restore EXIT
python3 - "$ENV" "$1" "$2" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
data = path.read_text().splitlines()
updates = {'ONMARU_DISCOVERY_API_ENABLED': str(sys.argv[2] == 'on').lower(),
           'ONMARU_KCONTENTS_RESEARCH_ENABLED': str(sys.argv[3] == 'on').lower()}
for key, value in updates.items():
    data = [line for line in data if not line.startswith(key + '=')]
    data.append(key + '=' + value)
path.write_text('\n'.join(data) + '\n')
path.chmod(0o600)
PY
compose() { docker compose --project-directory "$DIR" --env-file "$ENV" -f "$DIR/compose.yaml" "$@"; }
compose config --quiet
compose up -d --no-deps --force-recreate spring-api
for i in $(seq 1 60); do
    if [ "$(compose ps spring-api --format '{{.Health}}')" = healthy ]; then
        rm -f "$BACKUP"
        trap - EXIT
        printf 'Staging discovery=%s research=%s; Spring healthy.\n' "$1" "$2"
        exit 0
    fi
    sleep 3
done
restore
trap - EXIT
compose up -d --no-deps --force-recreate spring-api || true
echo 'New configuration failed health; old flags restored.' >&2
exit 1
