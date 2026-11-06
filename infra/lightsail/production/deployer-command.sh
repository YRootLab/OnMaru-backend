#!/bin/sh
set -eu
set -f
# Intentional field splitting: the forced SSH command accepts exactly four
# whitespace-delimited tokens, and the root script validates every argument.
# shellcheck disable=SC2086
set -- ${SSH_ORIGINAL_COMMAND:-}
set +f
case "${1:-}" in
    status)
        if [ "$#" -ne 2 ]; then
            echo "Allowed command: status <master-sha>" >&2
            exit 2
        fi
        exec /usr/bin/sudo -n /usr/local/sbin/onmaru-production-deploy-status "$2"
        ;;
    deploy)
        if [ "$#" -ne 4 ]; then
            echo "Allowed command: deploy <master-sha> <spring-image-digest> <github-actor>" >&2
            exit 2
        fi
        exec /usr/bin/sudo -n /usr/local/sbin/onmaru-production-deploy "$2" "$3" "$4"
        ;;
    deploy-ai)
        if [ "$#" -ne 3 ]; then
            echo "Allowed command: deploy-ai <ai-image-digest> <github-actor>" >&2
            exit 2
        fi
        IFS= read -r GH_TOKEN || { echo "Missing temporary GHCR token" >&2; exit 2; }
        printf '%s\n' "$GH_TOKEN" | /usr/bin/sudo -n /usr/local/sbin/onmaru-production-ai-deploy "$2" "$3"
        unset GH_TOKEN
        ;;
    rollback)
        if [ "$#" -ne 2 ]; then
            echo "Allowed command: rollback <github-actor>" >&2
            exit 2
        fi
        exec /usr/bin/sudo -n /usr/local/sbin/onmaru-production-rollback "$2"
        ;;
    *)
        echo "Allowed commands: status <master-sha>, deploy <master-sha> <spring-image-digest> <github-actor>, deploy-ai <ai-image-digest> <github-actor>, rollback <github-actor>" >&2
        exit 2
        ;;
esac
