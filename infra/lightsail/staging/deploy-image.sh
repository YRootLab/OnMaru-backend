#!/bin/sh
set -eu

# Installed as a root-owned copy in /usr/local/sbin by install-deployer.sh.
if [ "$(id -u)" -ne 0 ] || [ "$#" -ne 3 ]; then
    echo "Usage: deploy-image.sh <develop-sha> <spring-image-digest> <github-actor>" >&2
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

DIR=/opt/onmaru/staging-repo/infra/lightsail/staging
REPO=/opt/onmaru/staging-repo
ENV=$DIR/.env
IMAGE=ghcr.io/yrootlab/onmaru-backend/spring-api@$DIGEST
exec 9>/run/onmaru-staging.lock
flock -n 9 || { echo "Another staging operation is in progress" >&2; exit 1; }
[ "$(stat -c %a "$ENV")" = 600 ] || { echo "Staging .env must have mode 600" >&2; exit 1; }
[ "$(runuser -u ubuntu -- git -C "$REPO" branch --show-current)" = develop ] || {
    echo "Staging checkout must be on develop" >&2; exit 1;
}
[ -z "$(runuser -u ubuntu -- git -C "$REPO" status --porcelain)" ] || {
    echo "Staging checkout is dirty" >&2; exit 1;
}
if [ -n "$(docker compose --project-directory "$DIR" --env-file "$ENV" -f "$DIR/compose.yaml" ps --status running -q)" ]; then
    echo "Stop staging before deploying a new image" >&2
    exit 1
fi

GIT_TERMINAL_PROMPT=0 runuser -u ubuntu -- git -C "$REPO" fetch --quiet origin develop </dev/null
[ "$(runuser -u ubuntu -- git -C "$REPO" rev-parse origin/develop)" = "$SHA" ] || {
    echo "Requested SHA is not the latest origin/develop" >&2; exit 1;
}
runuser -u ubuntu -- git -C "$REPO" merge-base --is-ancestor HEAD "$SHA" || {
    echo "Staging checkout cannot fast-forward to requested SHA" >&2; exit 1;
}

DOCKER_CONFIG=$(mktemp -d /run/onmaru-staging-ghcr.XXXXXX)
chmod 700 "$DOCKER_CONFIG"
export DOCKER_CONFIG
trap 'rm -rf "$DOCKER_CONFIG"' EXIT HUP INT TERM
IFS= read -r GH_TOKEN || { echo "Missing temporary GHCR token" >&2; exit 1; }
[ -n "$GH_TOKEN" ] || { echo "Missing temporary GHCR token" >&2; exit 1; }
printf '%s\n' "$GH_TOKEN" | docker login ghcr.io -u "$ACTOR" --password-stdin >/dev/null
unset GH_TOKEN
docker pull "$IMAGE" >/dev/null
docker image inspect "$IMAGE" >/dev/null
ONMARU_SPRING_IMAGE="$IMAGE" docker compose --project-directory "$DIR" --env-file "$ENV" -f "$DIR/compose.yaml" config --quiet

runuser -u ubuntu -- git -C "$REPO" merge --ff-only --quiet "$SHA"
python3 - "$ENV" "$IMAGE" <<'PY'
from pathlib import Path
import os
import sys
import tempfile

path = Path(sys.argv[1])
image = sys.argv[2]
lines = path.read_text().splitlines(keepends=True)
matches = [i for i, line in enumerate(lines) if line.startswith("ONMARU_SPRING_IMAGE=")]
if len(matches) != 1:
    raise SystemExit("Expected exactly one ONMARU_SPRING_IMAGE entry")
old = lines[matches[0]].strip().split("=", 1)[1]
lines[matches[0]] = f"ONMARU_SPRING_IMAGE={image}\n"
fd, temp_name = tempfile.mkstemp(prefix=".env.", dir=path.parent)
try:
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, "w") as output:
        output.writelines(lines)
        output.flush()
        os.fsync(output.fileno())
    history = Path("/var/lib/onmaru/staging-previous-image")
    history.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    history.write_text(old + "\n")
    history.chmod(0o600)
    os.replace(temp_name, path)
except BaseException:
    if os.path.exists(temp_name):
        os.unlink(temp_name)
    raise
PY
docker compose --project-directory "$DIR" --env-file "$ENV" -f "$DIR/compose.yaml" config --quiet
printf 'Prepared staging image %s for develop %s; staging remains stopped.\n' "$DIGEST" "$SHA"
