#!/bin/sh
set -eu

LIGHTSAIL_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

compose() {
    docker compose --project-directory "$LIGHTSAIL_DIR" --env-file "$LIGHTSAIL_DIR/.env" \
        -f "$LIGHTSAIL_DIR/compose.yaml" "$@"
}

compose --profile tls run --rm certbot renew --webroot -w /var/www/certbot
compose exec -T nginx nginx -s reload
