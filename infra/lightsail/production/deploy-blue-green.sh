#!/bin/sh
set -eu

# Installed as a root-owned copy in /usr/local/sbin by install-deployer.sh.
if [ "$(id -u)" -ne 0 ] || [ "$#" -ne 3 ]; then
    echo "Usage: deploy-blue-green.sh <master-sha> <spring-image-digest> <github-actor>" >&2
    exit 2
fi

SHA=$1
DIGEST=$2
ACTOR=$3
case "$SHA" in *[!0-9a-f]*|'') echo "Invalid commit SHA" >&2; exit 2;; esac
[ "${#SHA}" -eq 40 ] || { echo "Invalid commit SHA length" >&2; exit 2; }
case "$DIGEST" in sha256:*) ;; *) echo "Invalid image digest" >&2; exit 2;; esac
HEX=${DIGEST#sha256:}
case "$HEX" in *[!0-9a-f]*|'') echo "Invalid image digest" >&2; exit 2;; esac
[ "${#HEX}" -eq 64 ] || { echo "Invalid image digest length" >&2; exit 2; }
case "$ACTOR" in *[!A-Za-z0-9_-]*|'') echo "Invalid GitHub actor" >&2; exit 2;; esac

REPO=/opt/onmaru/repo
DIR=$REPO/infra/lightsail
ENV=$DIR/.env
COMPOSE=$DIR/compose.yaml
UPSTREAM=$DIR/nginx/runtime/upstream.conf
STATE_DIR=/var/lib/onmaru
STATE=$STATE_DIR/production-active-slot
PREVIOUS_IMAGE=$STATE_DIR/production-previous-image
PREVIOUS_SLOT=$STATE_DIR/production-previous-slot
DEPLOYED_SHA=$STATE_DIR/production-deployed-sha
PREVIOUS_SHA=$STATE_DIR/production-previous-sha
HOLD=$STATE_DIR/production-deploy-hold
MIN_AVAILABLE_KB=131072
MAX_SWAP_DELTA_MB=192
HEALTH_ATTEMPTS=60
DRAIN_SECONDS=70
IMAGE=ghcr.io/yrootlab/onmaru-backend/spring-api@$DIGEST
SWITCHED=0
CANDIDATE_STARTED=0
ENV_UPDATED=0
SUCCESS=0
TARGET=
ACTIVE=
active_id=
OLD_IMAGE=
UPSTREAM_BACKUP=
DOCKER_CONFIG=
STATE_TEMP=
PREVIOUS_TEMP=
PREVIOUS_SLOT_TEMP=
DEPLOYED_SHA_TEMP=
PREVIOUS_SHA_TEMP=
ACTIVE_SHA=

compose() {
    docker compose --project-directory "$DIR" --env-file "$ENV" -f "$COMPOSE" "$@"
}

write_upstream() {
    slot=$1
    temp=$(mktemp "$DIR/nginx/runtime/.upstream.XXXXXX")
    {
        echo "upstream spring_backend {"
        echo "    server spring-$slot:8080;"
        echo "    keepalive 8;"
        echo "}"
    } > "$temp"
    chmod 0644 "$temp"
    mv -f "$temp" "$UPSTREAM"
}

restore_env() {
    [ "$ENV_UPDATED" -eq 1 ] || return 0
    python3 - "$ENV" "$OLD_IMAGE" <<'PY'
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
    ENV_UPDATED=0
}

rollback() {
    set +e
    if [ "$SWITCHED" -eq 1 ] && [ -n "$UPSTREAM_BACKUP" ] && [ -f "$UPSTREAM_BACKUP" ]; then
        if [ -n "$active_id" ] && [ "$(docker inspect --format '{{.State.Running}}' "$active_id" 2>/dev/null)" != true ]; then
            docker start "$active_id" >/dev/null 2>&1
        fi
        attempt=1
        while [ -n "$active_id" ] && [ "$attempt" -le 30 ]; do
            docker exec "$active_id" curl -fsS --max-time 2 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1 && break
            sleep 1
            attempt=$((attempt + 1))
        done
        cp "$UPSTREAM_BACKUP" "$UPSTREAM"
        nginx_id=$(compose ps -q nginx 2>/dev/null)
        if [ -n "$nginx_id" ]; then
            docker exec "$nginx_id" nginx -t >/dev/null 2>&1
            docker exec "$nginx_id" nginx -s reload >/dev/null 2>&1
        fi
    fi
    if [ "$CANDIDATE_STARTED" -eq 1 ] && [ -n "$TARGET" ]; then
        compose stop "spring-$TARGET" >/dev/null 2>&1
    fi
    restore_env
    set -e
}

cleanup() {
    [ -z "$UPSTREAM_BACKUP" ] || rm -f "$UPSTREAM_BACKUP"
    [ -z "$DOCKER_CONFIG" ] || rm -rf "$DOCKER_CONFIG"
    [ -z "$STATE_TEMP" ] || rm -f "$STATE_TEMP"
    [ -z "$PREVIOUS_TEMP" ] || rm -f "$PREVIOUS_TEMP"
    [ -z "$PREVIOUS_SLOT_TEMP" ] || rm -f "$PREVIOUS_SLOT_TEMP"
    [ -z "$DEPLOYED_SHA_TEMP" ] || rm -f "$DEPLOYED_SHA_TEMP"
    [ -z "$PREVIOUS_SHA_TEMP" ] || rm -f "$PREVIOUS_SHA_TEMP"
}

fail() {
    echo "$1" >&2
    exit 1
}

on_exit() {
    status=$?
    trap - EXIT HUP INT TERM
    if [ "$status" -ne 0 ] && [ "$SUCCESS" -eq 0 ]; then
        rollback
    fi
    cleanup
    exit "$status"
}

trap 'exit 1' HUP INT TERM
trap on_exit EXIT

exec 9>/run/onmaru-production-deploy.lock
flock -n 9 || { echo "Another production deployment is in progress" >&2; exit 1; }
[ -f "$ENV" ] && [ "$(stat -c %a "$ENV")" = 600 ] || fail "Production .env must exist with mode 600"
[ "$(runuser -u ubuntu -- git -C "$REPO" branch --show-current)" = master ] || fail "Production checkout must be on master"
[ -z "$(runuser -u ubuntu -- git -C "$REPO" status --porcelain)" ] || fail "Production checkout is dirty"

GIT_TERMINAL_PROMPT=0 runuser -u ubuntu -- git -C "$REPO" fetch --quiet origin master </dev/null
[ "$(runuser -u ubuntu -- git -C "$REPO" rev-parse origin/master)" = "$SHA" ] || fail "Requested SHA is not the latest origin/master"
runuser -u ubuntu -- git -C "$REPO" merge-base --is-ancestor HEAD "$SHA" || fail "Production checkout cannot fast-forward to requested SHA"

if [ -f "$HOLD" ] && [ "$(cat "$HOLD")" = "$SHA" ]; then
    fail "Requested master SHA is held after an operational rollback"
fi

if [ -f "$DEPLOYED_SHA" ] \
    && [ "$(cat "$DEPLOYED_SHA")" = "$SHA" ] \
    && [ "$(runuser -u ubuntu -- git -C "$REPO" rev-parse HEAD)" = "$SHA" ]; then
    curl -fsS --max-time 5 https://api.onmaru.site/auth/csrf >/dev/null \
        || fail "Already-deployed production health check failed"
    SUCCESS=1
    printf 'Already deployed master %s; no Blue-Green overlap required.\n' "$SHA"
    exit 0
fi

if docker ps --format '{{.Names}}' | grep -q '^onmaru-staging'; then
    fail "Stop onmaru-staging before production Blue-Green deployment"
fi

DOCKER_CONFIG=$(mktemp -d /run/onmaru-production-ghcr.XXXXXX)
chmod 700 "$DOCKER_CONFIG"
export DOCKER_CONFIG
IFS= read -r GH_TOKEN || fail "Missing temporary GHCR token"
[ -n "$GH_TOKEN" ] || fail "Missing temporary GHCR token"
printf '%s\n' "$GH_TOKEN" | docker login ghcr.io -u "$ACTOR" --password-stdin >/dev/null
unset GH_TOKEN
docker pull "$IMAGE" >/dev/null
docker image inspect "$IMAGE" >/dev/null

runuser -u ubuntu -- git -C "$REPO" merge --ff-only --quiet "$SHA"
mkdir -p "$DIR/nginx/runtime"
[ -f "$UPSTREAM" ] || fail "Nginx runtime upstream is missing; run bootstrap-blue-green.sh"
nginx_id=$(compose ps -q nginx)
[ -n "$nginx_id" ] || fail "Nginx is not running"
docker inspect --format '{{range .Mounts}}{{println .Destination}}{{end}}' "$nginx_id" | grep -qx /etc/nginx/onmaru || {
    fail "Nginx runtime upstream mount is missing; run bootstrap-blue-green.sh"
}

if [ -f "$STATE" ]; then
    ACTIVE=$(cat "$STATE")
    case "$ACTIVE" in legacy|blue|green) ;; *) fail "Invalid production active slot";; esac
elif [ -n "$(compose --profile legacy ps -q spring-api)" ]; then
    ACTIVE=legacy
elif [ -n "$(compose ps -q spring-blue)" ] && [ -z "$(compose ps -q spring-green)" ]; then
    ACTIVE=blue
elif [ -n "$(compose ps -q spring-green)" ] && [ -z "$(compose ps -q spring-blue)" ]; then
    ACTIVE=green
else
    fail "Cannot determine the active production Spring slot"
fi

case "$ACTIVE" in
    legacy) active_id=$(compose --profile legacy ps -q spring-api) ;;
    blue|green) active_id=$(compose ps -q "spring-$ACTIVE") ;;
esac
[ -n "$active_id" ] || fail "Active production Spring slot is not running"
docker exec "$active_id" curl -fsS --max-time 3 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1 || {
    fail "Active production Spring slot is not healthy"
}
ACTIVE_SHA=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$active_id" \
    | sed -n 's/^ONMARU_BUILD_GIT_SHA=//p' | tail -n 1)
case "$ACTIVE_SHA" in
    *[!0-9a-f]*|'')
        [ "$ACTIVE" = legacy ] || fail "Active Blue-Green slot has no valid build SHA"
        ACTIVE_SHA=legacy
        ;;
esac
if [ "$ACTIVE_SHA" != legacy ] && [ "${#ACTIVE_SHA}" -ne 40 ]; then
    [ "$ACTIVE" = legacy ] || fail "Active Blue-Green slot has an invalid build SHA"
    ACTIVE_SHA=legacy
fi

case "$ACTIVE" in
    legacy|blue) TARGET=green ;;
    green) TARGET=blue ;;
esac

OLD_IMAGE=$(sed -n 's/^ONMARU_SPRING_IMAGE=//p' "$ENV")
[ -n "$OLD_IMAGE" ] && [ "$(printf '%s\n' "$OLD_IMAGE" | wc -l)" -eq 1 ] || fail "Expected exactly one current Spring image"
python3 - "$ENV" "$IMAGE" <<'PY'
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
ENV_UPDATED=1
compose config --quiet || fail "Production Compose validation failed"

swap_before_mb=$(free -m | awk '/Swap:/ {print $3}')
compose up -d --no-deps "spring-$TARGET" || fail "Candidate Spring slot failed to start"
CANDIDATE_STARTED=1
candidate_id=$(compose ps -q "spring-$TARGET")
[ -n "$candidate_id" ] || fail "Candidate container is missing"

healthy=0
attempt=1
while [ "$attempt" -le "$HEALTH_ATTEMPTS" ]; do
    available_kb=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
    swap_now_mb=$(free -m | awk '/Swap:/ {print $3}')
    swap_delta_mb=$((swap_now_mb - swap_before_mb))
    [ "$swap_delta_mb" -lt 0 ] && swap_delta_mb=0
    [ "$available_kb" -ge "$MIN_AVAILABLE_KB" ] || fail "Available memory fell below the deployment floor"
    [ "$swap_delta_mb" -le "$MAX_SWAP_DELTA_MB" ] || fail "Swap growth exceeded the deployment budget"
    [ "$(docker inspect --format '{{.State.OOMKilled}}' "$candidate_id")" = false ] || fail "Candidate was OOMKilled"
    if docker exec "$candidate_id" curl -fsS --max-time 2 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
        healthy=1
        break
    fi
    sleep 2
    attempt=$((attempt + 1))
done
[ "$healthy" -eq 1 ] || fail "Candidate health check timed out"

UPSTREAM_BACKUP=$(mktemp /run/onmaru-upstream.XXXXXX)
cp "$UPSTREAM" "$UPSTREAM_BACKUP"
write_upstream "$TARGET"
SWITCHED=1
docker exec "$nginx_id" nginx -t >/dev/null || fail "Nginx candidate configuration is invalid"
docker exec "$nginx_id" nginx -s reload >/dev/null || fail "Nginx reload failed"

curl -fsS --max-time 5 https://api.onmaru.site/auth/csrf >/dev/null || fail "Public production smoke failed after traffic switch"
sleep "$DRAIN_SECONDS"

mkdir -p "$STATE_DIR"
chmod 0700 "$STATE_DIR"
STATE_TEMP=$(mktemp "$STATE_DIR/.production-active-slot.XXXXXX")
printf '%s\n' "$TARGET" > "$STATE_TEMP"
chmod 0600 "$STATE_TEMP"
PREVIOUS_TEMP=$(mktemp "$STATE_DIR/.production-previous-image.XXXXXX")
printf '%s\n' "$OLD_IMAGE" > "$PREVIOUS_TEMP"
chmod 0600 "$PREVIOUS_TEMP"
PREVIOUS_SLOT_TEMP=$(mktemp "$STATE_DIR/.production-previous-slot.XXXXXX")
printf '%s\n' "$ACTIVE" > "$PREVIOUS_SLOT_TEMP"
chmod 0600 "$PREVIOUS_SLOT_TEMP"
DEPLOYED_SHA_TEMP=$(mktemp "$STATE_DIR/.production-deployed-sha.XXXXXX")
printf '%s\n' "$SHA" > "$DEPLOYED_SHA_TEMP"
chmod 0600 "$DEPLOYED_SHA_TEMP"
PREVIOUS_SHA_TEMP=$(mktemp "$STATE_DIR/.production-previous-sha.XXXXXX")
printf '%s\n' "$ACTIVE_SHA" > "$PREVIOUS_SHA_TEMP"
chmod 0600 "$PREVIOUS_SHA_TEMP"

docker update --memory 448m --memory-swap 896m --cpus 1.2 "$candidate_id" >/dev/null

case "$ACTIVE" in
    legacy) compose --profile legacy stop spring-api >/dev/null ;;
    blue|green) compose stop "spring-$ACTIVE" >/dev/null ;;
esac
mv -f "$PREVIOUS_TEMP" "$PREVIOUS_IMAGE"
PREVIOUS_TEMP=
mv -f "$PREVIOUS_SLOT_TEMP" "$PREVIOUS_SLOT"
PREVIOUS_SLOT_TEMP=
mv -f "$PREVIOUS_SHA_TEMP" "$PREVIOUS_SHA"
PREVIOUS_SHA_TEMP=
mv -f "$STATE_TEMP" "$STATE"
STATE_TEMP=
mv -f "$DEPLOYED_SHA_TEMP" "$DEPLOYED_SHA"
DEPLOYED_SHA_TEMP=
rm -f "$HOLD"
SUCCESS=1
docker image prune -f --filter until=168h >/dev/null 2>&1 \
    || echo "Warning: deployment succeeded but old dangling images were not pruned." >&2
printf 'Deployed master %s digest %s to %s; previous slot %s is stopped.\n' "$SHA" "$DIGEST" "$TARGET" "$ACTIVE"
