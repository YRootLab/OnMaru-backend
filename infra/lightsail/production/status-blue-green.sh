#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ] || [ "$#" -ne 1 ]; then
    echo "Usage: status-blue-green.sh <master-sha>" >&2
    exit 2
fi

SHA=$1
case "$SHA" in *[!0-9a-f]*|'') echo "Invalid commit SHA" >&2; exit 2;; esac
[ "${#SHA}" -eq 40 ] || { echo "Invalid commit SHA length" >&2; exit 2; }

REPO=/opt/onmaru/repo
STATE_DIR=/var/lib/onmaru
DEPLOYED_SHA=$STATE_DIR/production-deployed-sha
HOLD=$STATE_DIR/production-deploy-hold

if [ -f "$HOLD" ] && [ "$(cat "$HOLD")" = "$SHA" ]; then
    printf 'held\n'
    exit 0
fi

if [ -f "$DEPLOYED_SHA" ] \
    && [ "$(cat "$DEPLOYED_SHA")" = "$SHA" ] \
    && [ "$(runuser -u ubuntu -- git -C "$REPO" branch --show-current)" = master ] \
    && [ -z "$(runuser -u ubuntu -- git -C "$REPO" status --porcelain)" ] \
    && [ "$(runuser -u ubuntu -- git -C "$REPO" rev-parse HEAD)" = "$SHA" ] \
    && curl -fsS --max-time 5 https://api.onmaru.site/auth/csrf >/dev/null; then
    printf 'deployed\n'
    exit 0
fi

printf 'deploy\n'
