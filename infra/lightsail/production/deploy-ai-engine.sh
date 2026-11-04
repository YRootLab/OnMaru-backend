#!/bin/sh
set -eu

# Installed root-owned; accepts an immutable AI image digest and GHCR token on stdin.
if [ "$(id -u)" -ne 0 ] || [ "$#" -ne 2 ]; then
    echo "Usage: deploy-ai-engine.sh <ai-image-digest> <github-actor>" >&2
    exit 2
fi
DIGEST=$1
ACTOR=$2
case "$DIGEST" in sha256:*) ;; *) echo "Invalid AI image digest" >&2; exit 2;; esac
HEX=${DIGEST#sha256:}
case "$HEX" in *[!0-9a-f]*|'') echo "Invalid AI image digest" >&2; exit 2;; esac
[ "${#HEX}" -eq 64 ] || { echo "Invalid AI image digest length" >&2; exit 2; }
case "$ACTOR" in *[!A-Za-z0-9_-]*|'') echo "Invalid GitHub actor" >&2; exit 2;; esac

DIR=/opt/onmaru/repo/infra/lightsail
ENV=$DIR/.env
IMAGE=ghcr.io/yrootlab/onmaru-ai-engine/ai-engine@$DIGEST
LOCK=/run/onmaru-ai-engine-deploy.lock
OLD_IMAGE=
PREVIOUS_ENV_IMAGE=
PREVIOUS_CONTAINER_ID=
CHANGED=0
SUCCESS=0
DOCKER_CONFIG=

compose() {
    docker compose --project-directory "$DIR" --env-file "$ENV" -f "$DIR/compose.yaml" --profile ai-engine "$@"
}

set_image() {
    image=$1
    python3 - "$ENV" "$image" <<'PY'
from pathlib import Path
import os
import sys
import tempfile

path = Path(sys.argv[1])
image = sys.argv[2]
lines = path.read_text().splitlines(keepends=True)
matches = [index for index, line in enumerate(lines) if line.startswith("ONMARU_AI_IMAGE=")]
if len(matches) != 1:
    raise SystemExit("Expected exactly one ONMARU_AI_IMAGE entry")
lines[matches[0]] = f"ONMARU_AI_IMAGE={image}\n"
fd, temporary = tempfile.mkstemp(prefix=".env.ai.", dir=path.parent)
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

restore_previous() {
    set +e
    if [ -n "$PREVIOUS_CONTAINER_ID" ] && [ -n "$OLD_IMAGE" ]; then
        set_image "$OLD_IMAGE" || return 1
        compose up -d --no-deps ai-engine >/dev/null || return 1
        attempt=1
        while [ "$attempt" -le 30 ]; do
            id=$(compose ps -q ai-engine)
            [ -n "$id" ] && [ "$(docker inspect --format '{{.State.Health.Status}}' "$id" 2>/dev/null)" = healthy ] && return 0
            sleep 2
            attempt=$((attempt + 1))
        done
        echo "CRITICAL: previous AI image did not become healthy" >&2
        return 1
    fi
    set_image "$PREVIOUS_ENV_IMAGE" || return 1
    compose stop ai-engine >/dev/null 2>&1
    return 0
}

cleanup() {
    [ -z "$DOCKER_CONFIG" ] || rm -rf "$DOCKER_CONFIG"
}

on_exit() {
    status=$?
    trap - EXIT HUP INT TERM
    if [ "$status" -ne 0 ] && [ "$SUCCESS" -eq 0 ] && [ "$CHANGED" -eq 1 ]; then
        restore_previous || echo "AI rollback needs operator attention" >&2
    fi
    cleanup
    exit "$status"
}
trap 'exit 1' HUP INT TERM
trap on_exit EXIT

exec 9>"$LOCK"
flock -n 9 || { echo "Another AI image deployment is in progress" >&2; exit 1; }
[ -f "$ENV" ] && [ "$(stat -c %a "$ENV")" = 600 ] || { echo "Production .env must exist with mode 600" >&2; exit 1; }
[ "$(sed -n 's/^ONMARU_AI_ENABLED=//p' "$ENV")" = true ] || { echo "AI deployment is disabled; enable it only after the 2GB staging gate" >&2; exit 1; }
for key in ONMARU_SECRET_TOURAPI_SERVICE_KEY_CURRENT ONMARU_SECRET_ODII_SERVICE_KEY_CURRENT \
    ONMARU_SECRET_GEMINI_API_KEY_CURRENT ONMARU_SECRET_OAUTH_CLIENT_SECRET_CURRENT \
    ONMARU_SECRET_OTLP_EXPORTER_TOKEN_CURRENT ONMARU_SECRET_INTERNAL_AI_SERVICE_TOKEN_CURRENT; do
    grep -Eq "^${key}=.+$" "$ENV" || { echo "AI deployment is missing a required secret" >&2; exit 1; }
done
total_kb=$(awk '/MemTotal:/ {print $2}' /proc/meminfo)
[ "$total_kb" -ge 1700000 ] || { echo "AI deployment requires the upgraded 2GB Lightsail host" >&2; exit 1; }
available_kb=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
[ "$available_kb" -ge 524288 ] || { echo "Available memory is below the AI deployment floor" >&2; exit 1; }

PREVIOUS_ENV_IMAGE=$(sed -n 's/^ONMARU_AI_IMAGE=//p' "$ENV")
[ -n "$PREVIOUS_ENV_IMAGE" ] && [ "$(printf '%s\n' "$PREVIOUS_ENV_IMAGE" | wc -l)" -eq 1 ] || { echo "Expected exactly one current AI image" >&2; exit 1; }
PREVIOUS_CONTAINER_ID=$(compose ps -q ai-engine)
if [ -n "$PREVIOUS_CONTAINER_ID" ]; then
    OLD_IMAGE=$(docker inspect --format '{{.Config.Image}}' "$PREVIOUS_CONTAINER_ID")
fi
DOCKER_CONFIG=$(mktemp -d /run/onmaru-ai-ghcr.XXXXXX)
chmod 700 "$DOCKER_CONFIG"
export DOCKER_CONFIG
IFS= read -r GH_TOKEN || { echo "Missing temporary GHCR token" >&2; exit 1; }
[ -n "$GH_TOKEN" ] || { echo "Missing temporary GHCR token" >&2; exit 1; }
printf '%s\n' "$GH_TOKEN" | docker login ghcr.io -u "$ACTOR" --password-stdin >/dev/null
unset GH_TOKEN
docker pull "$IMAGE" >/dev/null
docker image inspect "$IMAGE" >/dev/null
set_image "$IMAGE"
CHANGED=1
compose config --quiet
compose up -d --no-deps ai-engine

healthy=0
attempt=1
while [ "$attempt" -le 45 ]; do
    container_id=$(compose ps -q ai-engine)
    if [ -n "$container_id" ]; then
        [ "$(docker inspect --format '{{.State.OOMKilled}}' "$container_id")" = false ] || { echo "AI image was OOMKilled" >&2; exit 1; }
        if [ "$(docker inspect --format '{{.State.Health.Status}}' "$container_id" 2>/dev/null || true)" = healthy ]; then
            healthy=1
            break
        fi
    fi
    sleep 2
    attempt=$((attempt + 1))
done
[ "$healthy" -eq 1 ] || { echo "AI image health check timed out" >&2; exit 1; }
available_kb=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
[ "$available_kb" -ge 262144 ] || { echo "Available memory fell below the post-deploy floor" >&2; exit 1; }
SUCCESS=1
printf 'Deployed AI image digest %s; the previous digest remains available for a separate rollback.\n' "$DIGEST"
