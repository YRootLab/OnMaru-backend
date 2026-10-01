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
ENV="$DIR/.env"
COMPOSE="$DIR/compose.yaml"
if [ ! -f "$ENV" ] || [ "$(stat -c %a "$ENV")" != 600 ]; then
    echo "Staging .env must exist with mode 600." >&2
    exit 1
fi
if ! grep -qx 'ONMARU_POSTGRES_DB=onmaru_staging' "$ENV"; then
    echo "Refusing to start: staging DB name must be onmaru_staging." >&2
    exit 1
fi
if grep -q 'REPLACE_WITH_' "$ENV"; then
    echo "Refusing to start with placeholder staging secrets." >&2
    exit 1
fi
systemctl cat onmaru-staging-auto-stop.timer >/dev/null
compose() {
    docker compose --project-directory "$DIR" --env-file "$ENV" -f "$COMPOSE" "$@"
}
started=0
cleanup_on_error() {
    if [ "$started" -eq 1 ]; then
        compose stop spring-api postgres || true
    fi
}
trap cleanup_on_error EXIT
compose config --quiet
if [ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' onmaru-lightsail-postgres-1 2>/dev/null)" != healthy ]; then
    echo "Production PostgreSQL is not healthy; leave staging asleep." >&2
    exit 1
fi
if [ "$(compose ps spring-api --format '{{.Health}}')" = healthy ]; then
    systemctl restart onmaru-staging-auto-stop.timer
    printf 'Staging is already ready; automatic stop was renewed for 2 hours.\n'
    exit 0
fi
started=1
compose up -d postgres
for i in $(seq 1 30); do
    if [ "$(compose ps postgres --format '{{.Health}}')" = healthy ]; then break; fi
    sleep 2
done
if [ "$(compose ps postgres --format '{{.Health}}')" != healthy ]; then
    echo "Staging PostgreSQL did not become healthy." >&2
    exit 1
fi
compose --profile migration run --rm migrate migrate
compose up -d spring-api
for i in $(seq 1 60); do
    if [ "$(compose ps spring-api --format '{{.Health}}')" = healthy ]; then break; fi
    sleep 3
done
if [ "$(compose ps spring-api --format '{{.Health}}')" != healthy ]; then
    echo "Staging Spring did not become healthy." >&2
    exit 1
fi
compose exec -T postgres sh -ec 'psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 --file /opt/onmaru/seed.sql' >/dev/null
systemctl restart onmaru-staging-auto-stop.timer
started=0
printf 'Staging is ready; automatic stop is scheduled in 2 hours.\n'
