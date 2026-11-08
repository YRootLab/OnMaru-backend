#!/bin/sh
set -eu
set -- ${SSH_ORIGINAL_COMMAND:-}
if [ "$#" -ne 5 ] || [ "$1" != deploy ]; then
    echo "Allowed command: deploy <develop-sha> <spring-image-digest> <ai-image-digest> <github-actor>" >&2
    exit 2
fi
exec /usr/bin/sudo -n /usr/local/sbin/onmaru-staging-deploy-image "$2" "$3" "$4" "$5"
