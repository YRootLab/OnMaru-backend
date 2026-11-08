#!/bin/sh
set -eu
case "${SSH_ORIGINAL_COMMAND:-}" in
    version)
        printf 'staging-ai-v1\n'
        ;;
    deploy\ *)
        set -- ${SSH_ORIGINAL_COMMAND:-}
        if [ "$#" -ne 5 ] || [ "$1" != deploy ]; then
            echo "Allowed command: deploy <develop-sha> <spring-image-digest> <ai-image-digest> <github-actor>" >&2
            exit 2
        fi
        exec /usr/bin/sudo -n /usr/local/sbin/onmaru-staging-deploy-image "$2" "$3" "$4" "$5"
        ;;
    *)
        echo "Allowed commands: version, deploy <develop-sha> <spring-image-digest> <ai-image-digest> <github-actor>" >&2
        exit 2
        ;;
esac
