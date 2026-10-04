#!/bin/sh
set -eu
if [ "$(id -u)" -ne 0 ]; then
    echo "Run with sudo from the production Lightsail checkout." >&2
    exit 2
fi
REPO=/opt/onmaru/repo
DIR=$REPO/infra/lightsail
ENV=$DIR/.env
COMPOSE=$DIR/compose.yaml
UPSTREAM=$DIR/nginx/runtime/upstream.conf

cd "$DIR"
[ "$(runuser -u ubuntu -- git -C "$REPO" branch --show-current)" = master ] || {
    echo "Production checkout must be on master." >&2
    exit 1
}
[ -f "$ENV" ] && [ "$(stat -c %a "$ENV")" = 600 ] || {
    echo "Production .env must exist with mode 600." >&2
    exit 1
}
docker exec onmaru-lightsail-spring-api-1 curl -fsS --max-time 3 http://127.0.0.1:8080/actuator/health >/dev/null
mkdir -p "$DIR/nginx/runtime"
cat > "$UPSTREAM" <<'UPSTREAM'
upstream spring_backend {
    server spring-api:8080;
    keepalive 8;
}
UPSTREAM
chmod 0644 "$UPSTREAM"
docker compose --project-directory "$DIR" --env-file "$ENV" -f "$COMPOSE" --profile legacy config --quiet
docker compose --project-directory "$DIR" --env-file "$ENV" -f "$COMPOSE" --profile legacy up -d --no-deps --force-recreate nginx
nginx_id=$(docker compose --project-directory "$DIR" --env-file "$ENV" -f "$COMPOSE" ps -q nginx)
docker exec "$nginx_id" nginx -t >/dev/null
docker exec onmaru-lightsail-spring-api-1 curl -fsS --max-time 3 http://127.0.0.1:8080/actuator/health >/dev/null
curl -fsS --max-time 5 https://api.onmaru.site/auth/csrf >/dev/null
printf 'Blue-Green bootstrap completed with the legacy Spring container still active.\n'
