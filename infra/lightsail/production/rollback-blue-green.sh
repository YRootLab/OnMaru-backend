#!/bin/sh
set -eu

# Rolls traffic back only to the stopped slot retained by the last successful
# Blue-Green deployment. Database migrations are never reversed here.
if [ "$(id -u)" -ne 0 ] || [ "$#" -ne 1 ]; then
    echo "Usage: rollback-blue-green.sh <github-actor>" >&2
    exit 2
fi

ACTOR=$1
case "$ACTOR" in *[!A-Za-z0-9_-]*|'') echo "Invalid GitHub actor" >&2; exit 2;; esac

REPO=/opt/onmaru/repo
DIR=$REPO/infra/lightsail
ENV=$DIR/.env
COMPOSE=$DIR/compose.yaml
UPSTREAM=$DIR/nginx/runtime/upstream.conf
STATE_DIR=/var/lib/onmaru/production-state
CURRENT_STATE=$STATE_DIR/current
MIN_AVAILABLE_KB=131072
MAX_SWAP_DELTA_MB=192
HEALTH_ATTEMPTS=60
DRAIN_SECONDS=70
SWITCHED=0
TARGET_STARTED=0
ENV_UPDATED=0
SUCCESS=0
UPSTREAM_BACKUP=
OLD_ENV_IMAGE=
ACTIVE=
TARGET=
active_id=
target_id=
CURRENT_SHA=
ROLLBACK_SHA=
CURRENT_IMAGE=
ROLLBACK_IMAGE=

state_value() {
    key=$1
    [ -L "$CURRENT_STATE" ] && [ -f "$CURRENT_STATE/$key" ] || return 1
    cat "$CURRENT_STATE/$key"
}

commit_state() {
    next=$(mktemp -d "$STATE_DIR/.next.XXXXXX")
    printf '%s\n' "$TARGET" > "$next/active-slot"
    printf '%s\n' "$CURRENT_IMAGE" > "$next/previous-image"
    printf '%s\n' "$ACTIVE" > "$next/previous-slot"
    printf '%s\n' "$ROLLBACK_SHA" > "$next/deployed-sha"
    printf '%s\n' "$CURRENT_SHA" > "$next/previous-sha"
    printf '%s\n' "$CURRENT_SHA" > "$next/deploy-hold"
    chmod 0600 "$next"/*
    generation="$STATE_DIR/generation-$ROLLBACK_SHA-$(date +%s)-$$"
    mv "$next" "$generation"
    next_link="$STATE_DIR/.current.$$"
    rm -f "$next_link"
    ln -s "$(basename "$generation")" "$next_link"
    mv -Tf "$next_link" "$CURRENT_STATE"
}

verify_target_stable() {
    elapsed=0
    while [ "$elapsed" -lt "$DRAIN_SECONDS" ]; do
        available_kb=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
        swap_now_mb=$(free -m | awk '/Swap:/ {print $3}')
        swap_delta_mb=$((swap_now_mb - swap_before_mb))
        [ "$swap_delta_mb" -lt 0 ] && swap_delta_mb=0
        [ "$available_kb" -ge "$MIN_AVAILABLE_KB" ] || fail "Available memory fell below the rollback floor after traffic switch"
        [ "$swap_delta_mb" -le "$MAX_SWAP_DELTA_MB" ] || fail "Swap growth exceeded the rollback budget after traffic switch"
        [ "$(docker inspect --format '{{.State.OOMKilled}}' "$target_id")" = false ] || fail "Rollback slot was OOMKilled after traffic switch"
        docker exec "$target_id" curl -fsS --max-time 2 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1 \
            || fail "Rollback slot became unhealthy after traffic switch"
        sleep 2
        elapsed=$((elapsed + 2))
    done
    curl -fsS --max-time 5 https://api.onmaru.site/auth/csrf >/dev/null \
        || fail "Public production smoke failed after the rollback drain window"
}

compose() {
    docker compose --project-directory "$DIR" --env-file "$ENV" -f "$COMPOSE" "$@"
}

write_upstream() {
    slot=$1
    case "$slot" in
        legacy) backend=spring-api ;;
        blue|green) backend=spring-$slot ;;
        *) fail "Invalid rollback upstream slot" ;;
    esac
    temp=$(mktemp "$DIR/nginx/runtime/.upstream.XXXXXX")
    {
        echo "upstream spring_backend {"
        echo "    server $backend:8080;"
        echo "    keepalive 8;"
        echo "}"
    } > "$temp"
    chmod 0644 "$temp"
    mv -f "$temp" "$UPSTREAM"
}

write_env_image() {
    image=$1
    python3 - "$ENV" "$image" <<'PY'
from pathlib import Path
import os
import sys
import tempfile

path = Path(sys.argv[1])
image = sys.argv[2]
lines = path.read_text().splitlines(keepends=True)
matches = [index for index, line in enumerate(lines) if line.startswith("ONMARU_SPRING_IMAGE=")]
if len(matches) != 1:
    raise SystemExit("Expected exactly one ONMARU_SPRING_IMAGE entry")
lines[matches[0]] = f"ONMARU_SPRING_IMAGE={image}\n"
fd, temporary = tempfile.mkstemp(prefix=".env.", dir=path.parent)
try:
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, "w") as output:
        output.writelines(lines)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)
except BaseException:
    if os.path.exists(temporary):
        os.unlink(temporary)
    raise
PY
}

rollback_failure() {
    set +e
    active_recovered=1
    route_recovered=1
    if [ "$SWITCHED" -eq 1 ]; then
        active_recovered=0
        route_recovered=0
    fi
    if [ -n "$active_id" ] && [ "$(docker inspect --format '{{.State.Running}}' "$active_id" 2>/dev/null)" != true ]; then
        docker start "$active_id" >/dev/null 2>&1
    fi
    if [ "$SWITCHED" -eq 1 ]; then
        attempt=1
        while [ -n "$active_id" ] && [ "$attempt" -le 30 ]; do
            if docker exec "$active_id" curl -fsS --max-time 2 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
                active_recovered=1
                break
            fi
            sleep 1
            attempt=$((attempt + 1))
        done
    fi
    if [ "$SWITCHED" -eq 1 ] && [ -n "$UPSTREAM_BACKUP" ] && [ -f "$UPSTREAM_BACKUP" ]; then
        if [ "$active_recovered" -eq 1 ]; then
            if cp "$UPSTREAM_BACKUP" "$UPSTREAM"; then
                nginx_id=$(compose ps -q nginx 2>/dev/null)
                if [ -n "$nginx_id" ] \
                    && docker exec "$nginx_id" nginx -t >/dev/null 2>&1 \
                    && docker exec "$nginx_id" nginx -s reload >/dev/null 2>&1; then
                    route_recovered=1
                fi
            fi
        fi
        if [ "$route_recovered" -ne 1 ]; then
            echo "CRITICAL: rejected slot or route did not recover; preserving the healthy rollback route and environment" >&2
        fi
    fi
    if [ "$route_recovered" -eq 1 ] && [ "$TARGET_STARTED" -eq 1 ] && [ -n "$target_id" ]; then
        docker stop "$target_id" >/dev/null 2>&1
    fi
    if [ "$route_recovered" -eq 1 ] && [ "$ENV_UPDATED" -eq 1 ] && [ -n "$OLD_ENV_IMAGE" ]; then
        write_env_image "$OLD_ENV_IMAGE"
    fi
    set -e
}

on_exit() {
    status=$?
    trap - EXIT HUP INT TERM
    if [ "$status" -ne 0 ] && [ "$SUCCESS" -eq 0 ]; then
        rollback_failure
    fi
    [ -z "$UPSTREAM_BACKUP" ] || rm -f "$UPSTREAM_BACKUP"
    exit "$status"
}

fail() {
    echo "$1" >&2
    exit 1
}

trap 'exit 1' HUP INT TERM
trap on_exit EXIT

exec 9>/run/onmaru-production-deploy.lock
flock -n 9 || fail "Another production deployment or rollback is in progress"
[ -f "$ENV" ] && [ "$(stat -c %a "$ENV")" = 600 ] || fail "Production .env must exist with mode 600"
[ "$(runuser -u ubuntu -- git -C "$REPO" branch --show-current)" = master ] || fail "Production checkout must be on master"
[ -z "$(runuser -u ubuntu -- git -C "$REPO" status --porcelain)" ] || fail "Production checkout is dirty"
[ -z "$(state_value deploy-hold 2>/dev/null || true)" ] || fail "A production rollback hold already exists"
if docker ps --format '{{.Names}}' | grep -q '^onmaru-staging'; then
    fail "Stop onmaru-staging before production rollback"
fi

for required in active-slot previous-image previous-slot deployed-sha previous-sha; do
    required="$CURRENT_STATE/$required"
    [ -f "$required" ] || fail "Required rollback state is missing: $required"
done
[ -f "$UPSTREAM" ] || fail "Required rollback state is missing: $UPSTREAM"

ACTIVE=$(state_value active-slot)
case "$ACTIVE" in
    blue|green) ;;
    *) fail "Rollback requires an active Blue-Green slot" ;;
esac
TARGET=$(state_value previous-slot)
case "$TARGET" in legacy|blue|green) ;; *) fail "Invalid retained rollback slot" ;; esac
[ "$TARGET" != "$ACTIVE" ] || fail "Rollback slot must differ from the active slot"

CURRENT_SHA=$(state_value deployed-sha)
ROLLBACK_SHA=$(state_value previous-sha)
case "$CURRENT_SHA" in *[!0-9a-f]*|'') fail "Invalid deployed SHA state";; esac
[ "${#CURRENT_SHA}" -eq 40 ] || fail "Invalid deployed SHA length"
if [ "$ROLLBACK_SHA" != legacy ]; then
    case "$ROLLBACK_SHA" in *[!0-9a-f]*|'') fail "Invalid rollback SHA state";; esac
    [ "${#ROLLBACK_SHA}" -eq 40 ] || fail "Invalid rollback SHA length"
fi
CURRENT_IMAGE=$(sed -n 's/^ONMARU_SPRING_IMAGE=//p' "$ENV")
ROLLBACK_IMAGE=$(state_value previous-image)
[ -n "$CURRENT_IMAGE" ] && [ -n "$ROLLBACK_IMAGE" ] || fail "Rollback image state is empty"

active_id=$(compose ps -q "spring-$ACTIVE")
case "$TARGET" in
    legacy) target_id=$(compose --profile legacy ps -aq spring-api) ;;
    blue|green) target_id=$(compose ps -aq "spring-$TARGET") ;;
esac
[ -n "$active_id" ] || fail "Active production Spring slot is not running"
[ -n "$target_id" ] || fail "Retained previous Spring slot is missing"
[ "$(docker inspect --format '{{.Config.Image}}' "$target_id")" = "$ROLLBACK_IMAGE" ] || fail "Retained slot image does not match rollback state"
docker exec "$active_id" curl -fsS --max-time 3 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1 || fail "Current production slot is not healthy"

docker update --memory 384m --memory-swap 768m --cpus 0.55 "$target_id" >/dev/null
swap_before_mb=$(free -m | awk '/Swap:/ {print $3}')
docker start "$target_id" >/dev/null
TARGET_STARTED=1

healthy=0
attempt=1
while [ "$attempt" -le "$HEALTH_ATTEMPTS" ]; do
    available_kb=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
    swap_now_mb=$(free -m | awk '/Swap:/ {print $3}')
    swap_delta_mb=$((swap_now_mb - swap_before_mb))
    [ "$swap_delta_mb" -lt 0 ] && swap_delta_mb=0
    [ "$available_kb" -ge "$MIN_AVAILABLE_KB" ] || fail "Available memory fell below the rollback floor"
    [ "$swap_delta_mb" -le "$MAX_SWAP_DELTA_MB" ] || fail "Swap growth exceeded the rollback budget"
    [ "$(docker inspect --format '{{.State.OOMKilled}}' "$target_id")" = false ] || fail "Rollback slot was OOMKilled"
    if docker exec "$target_id" curl -fsS --max-time 2 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
        healthy=1
        break
    fi
    sleep 2
    attempt=$((attempt + 1))
done
[ "$healthy" -eq 1 ] || fail "Rollback slot health check timed out"

nginx_id=$(compose ps -q nginx)
[ -n "$nginx_id" ] || fail "Nginx is not running"
UPSTREAM_BACKUP=$(mktemp /run/onmaru-upstream.XXXXXX)
cp "$UPSTREAM" "$UPSTREAM_BACKUP"
write_upstream "$TARGET"
SWITCHED=1
docker exec "$nginx_id" nginx -t >/dev/null || fail "Nginx rollback configuration is invalid"
docker exec "$nginx_id" nginx -s reload >/dev/null || fail "Nginx rollback reload failed"
curl -fsS --max-time 5 https://api.onmaru.site/auth/csrf >/dev/null || fail "Public production smoke failed after rollback switch"
verify_target_stable

OLD_ENV_IMAGE=$CURRENT_IMAGE
write_env_image "$ROLLBACK_IMAGE"
ENV_UPDATED=1
compose config --quiet || fail "Production Compose validation failed after rollback"
docker update --memory 448m --memory-swap 896m --cpus 1.2 "$target_id" >/dev/null
docker stop "$active_id" >/dev/null || fail "Rejected $ACTIVE slot could not be stopped"
commit_state
SUCCESS=1
docker image prune -f --filter until=168h >/dev/null 2>&1 \
    || echo "Warning: rollback succeeded but old dangling images were not pruned." >&2
printf 'Rolled production back from %s to %s on slot %s; rejected SHA is held.\n' "$CURRENT_SHA" "$ROLLBACK_SHA" "$TARGET"
