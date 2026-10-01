#!/bin/sh
set -eu
case "${SSH_ORIGINAL_COMMAND:-}" in
    start)
        exec /usr/bin/sudo -n /opt/onmaru/staging-repo/infra/lightsail/staging/start.sh
        ;;
    stop)
        exec /usr/bin/sudo -n /opt/onmaru/staging-repo/infra/lightsail/staging/stop.sh
        ;;
    status)
        exec /usr/bin/sudo -n /opt/onmaru/staging-repo/infra/lightsail/staging/status.sh
        ;;
    *)
        echo "Allowed commands: start, stop, status" >&2
        exit 2
        ;;
esac
