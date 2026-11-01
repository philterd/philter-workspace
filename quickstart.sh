#!/usr/bin/env bash
#
# Builds and starts the development stack in docker-compose.yaml, waits until Philter Workspace is
# healthy, and gives Philter's admin user its first password, so you can sign in straight away.
#
# The development stack is not for anything reachable from a network. It loads demo data, serves
# plain HTTP, and its demo services run with fixed or no credentials, so it publishes every port on
# 127.0.0.1 only. See docker-compose.prod.yaml for a deployment.
#
# Usage:
#   ./quickstart.sh
#
# Environment:
#   PHILTER_WORKSPACE_PORT   Host port for Philter Workspace (default 8080).
#   PHILTER_WORKSPACE_BIND   Host IPv4 address for Philter Workspace and the policy editor
#                            (default 127.0.0.1). 0.0.0.0 opens them to the network.
#   ADMIN_PASSWORD           admin's first password, 16 to 72 characters. Generated when unset.
#   PHILTER_WORKSPACE_STORE  sqlite (default) or mongodb.
#   OPENSEARCH_PORT, POLICY_EDITOR_PORT, MINIO_PORT, MINIO_CONSOLE_PORT, POSTGRES_PORT, MONGODB_PORT,
#   VALKEY_PORT
#                            Host ports of the other services, when their defaults are taken.
#
# Safe to run again: it keeps the keys already in .env and never replaces a password admin already has.

set -euo pipefail

cd "$(dirname "$0")"

PORT="${PHILTER_WORKSPACE_PORT:-8080}"
STORE="${PHILTER_WORKSPACE_STORE:-sqlite}"
APP_CONTAINER="philter-workspace-app"
WAIT_SECONDS=600

fail() {
    echo "quickstart: $*" >&2
    exit 1
}

for tool in docker openssl; do
    command -v "$tool" > /dev/null || fail "$tool is required."
done
docker compose version > /dev/null 2>&1 || fail "Docker Compose v2 (docker compose) is required."

# --- .env and its keys -------------------------------------------------------------------------

if [ ! -f .env ]; then
    cp .env.example .env
    echo "Created .env from .env.example."
fi

# Sets $1 in .env to the output of the rest of the arguments, unless .env already gives it a value
# other than the .env.example placeholder.
generate() {
    local name="$1"
    shift
    if grep -q "^${name}=." .env && ! grep -q "^${name}=replace-me" .env; then
        return
    fi
    local value
    value="$("$@")"
    grep -v "^${name}=" .env > .env.tmp || true
    printf '%s=%s\n' "$name" "$value" >> .env.tmp
    mv .env.tmp .env
    echo "Generated ${name} in .env."
}
bootstrap_key() {
    printf 'sk_%s' "$(openssl rand -hex 16)"
}
generate PHILTER_WORKSPACE_CRYPTO_SECRET openssl rand -base64 32
generate VALKEY_PASSWORD openssl rand -hex 24
generate PHILTER_ENCRYPTION_KEY openssl rand -base64 32
# Philter seeds this key onto its admin user on its first start only, so it is never changed afterwards.
generate PHILTER_BOOTSTRAP_API_KEY bootstrap_key
chmod 600 .env

BOOTSTRAP_KEY="$(sed -n 's/^PHILTER_BOOTSTRAP_API_KEY=//p' .env | tail -n 1)"

# --- Host ports --------------------------------------------------------------------------------

profile=()
if [ "$STORE" = "mongodb" ]; then
    profile=(--profile mongodb)
fi
export PHILTER_WORKSPACE_PORT="$PORT" PHILTER_WORKSPACE_STORE="$STORE"

# The variable that moves each service's host port in docker-compose.yaml.
port_variable() {
    case "$1" in
        philter-workspace) echo PHILTER_WORKSPACE_PORT ;;
        valkey) echo VALKEY_PORT ;;
        policy-editor) echo POLICY_EDITOR_PORT ;;
        opensearch) echo OPENSEARCH_PORT ;;
        postgres) echo POSTGRES_PORT ;;
        mongodb) echo MONGODB_PORT ;;
        minio) [ "$2" = "9001" ] && echo MINIO_CONSOLE_PORT || echo MINIO_PORT ;;
        *) echo "" ;;
    esac
}

# Every host port the stack publishes must be free, unless this stack's own container holds it.
ours=" "
for id in $(docker ps -q --filter "name=^philter-workspace-"); do
    ours+="$(docker inspect -f '{{range $p, $b := .NetworkSettings.Ports}}{{range $b}}{{.HostPort}} {{end}}{{end}}' "$id") "
done
conflicts=()
service=""
while IFS= read -r line; do
    if [[ "$line" =~ ^\ \ ([a-z0-9-]+):$ ]]; then
        service="${BASH_REMATCH[1]}"
    elif [[ "$line" =~ published:\ \"?([0-9]+)\"?$ ]]; then
        port="${BASH_REMATCH[1]}"
        if [[ "$ours" != *" ${port} "* ]] && (echo > "/dev/tcp/127.0.0.1/${port}") 2> /dev/null; then
            variable="$(port_variable "$service" "$port")"
            conflicts+=("  ${service}: host port ${port} is in use${variable:+. Set ${variable} to another port.}")
        fi
    fi
done < <(docker compose "${profile[@]}" config)
if [ "${#conflicts[@]}" -gt 0 ]; then
    printf '%s\n' "${conflicts[@]}" >&2
    fail "free those ports or move them, for example: PHILTER_WORKSPACE_PORT=8090 OPENSEARCH_PORT=9201 ./quickstart.sh"
fi

# --- Build and start ---------------------------------------------------------------------------

BIND="${PHILTER_WORKSPACE_BIND:-127.0.0.1}"
if [ "$BIND" != "127.0.0.1" ]; then
    echo "WARNING: PHILTER_WORKSPACE_BIND=${BIND} publishes Philter Workspace and the policy editor beyond this" >&2
    echo "machine. The development stack loads demo data and serves plain HTTP; it is not for anything" >&2
    echo "reachable from a network. See docker-compose.prod.yaml for a deployment." >&2
fi

echo "Building and starting the stack. The first build takes several minutes."
docker compose "${profile[@]}" up -d --build

# --- Wait for Philter Workspace ----------------------------------------------------------------

echo "Waiting for Philter Workspace to report healthy."
deadline=$((SECONDS + WAIT_SECONDS))
while true; do
    health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$APP_CONTAINER" 2> /dev/null || true)"
    [ "$health" = "healthy" ] && break
    if [ "$health" = "unhealthy" ] || [ "$SECONDS" -ge "$deadline" ]; then
        fail "Philter Workspace did not become healthy (${health:-not running}). See: docker compose logs philter-workspace"
    fi
    sleep 5
done

# Philter calls, made from inside the Philter Workspace container, which has curl and is on Philter's network.
philter() {
    docker compose exec -T philter-workspace curl -s -o /dev/null -w '%{http_code}' \
        -H "Authorization: Bearer ${BOOTSTRAP_KEY}" "$@"
}

# Philter seeds its bootstrap key just after it starts answering, so wait until the key is accepted.
deadline=$((SECONDS + 120))
until [ "$(philter http://philter:8080/api/limits)" = "200" ]; do
    [ "$SECONDS" -lt "$deadline" ] || fail "Philter did not accept the bootstrap key. See: docker compose logs philter"
    sleep 2
done

# --- admin's first password --------------------------------------------------------------------

generated=false
if [ -z "${ADMIN_PASSWORD:-}" ]; then
    ADMIN_PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)"
    generated=true
fi
[ "${#ADMIN_PASSWORD}" -ge 16 ] || fail "ADMIN_PASSWORD must be at least 16 characters."

escaped="$(printf '%s' "$ADMIN_PASSWORD" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"
# The password goes on standard input, not the command line.
status="$(printf '{"password":"%s"}' "$escaped" | philter -X PUT -H "Content-Type: application/json" \
    --data-binary @- http://philter:8080/api/users/admin/password)"

url="http://localhost:${PORT}"
echo
case "$status" in
    204)
        echo "Philter Workspace is ready at ${url}"
        echo "Sign in as: admin"
        if [ "$generated" = true ]; then
            echo "Password:   ${ADMIN_PASSWORD}"
            echo "(Generated and shown only now. Change it under Settings after signing in.)"
        else
            echo "Password:   the ADMIN_PASSWORD you gave."
        fi
        ;;
    409)
        echo "Philter Workspace is ready at ${url}"
        echo "Sign in as admin with the password it already has; it was left unchanged."
        ;;
    400)
        fail "Philter refused the password: it must be 16 characters to 72 bytes."
        ;;
    *)
        fail "Philter answered HTTP ${status} when setting admin's password. See: docker compose logs philter"
        ;;
esac
echo
echo "Stop the stack with: docker compose down   (add -v to also delete its data)"
