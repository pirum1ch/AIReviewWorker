#!/usr/bin/env bash
# Build (if needed) and launch the LLM Worker.
#
# Required vars (README.md §5.1) are read from a .env file next to this script if present;
# anything still missing is prompted for interactively. Already-exported env vars win over .env.
# BACKEND_URL (README.md §5.2, Backend Self-Registration) is optional and never prompted for -- set
# it in .env to announce this backend to the Gateway automatically at startup, leave it unset to
# register the backend via the Gateway's admin API or SQL instead.
#
# Usage:
#   ./start-worker.sh            # build only if target/llm-worker.jar is missing, then run
#   ./start-worker.sh --rebuild  # force a rebuild first
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

ENV_FILE="$SCRIPT_DIR/.env"
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

command -v java >/dev/null 2>&1 || {
  echo "java not found on PATH (need JDK 21). Set JAVA_HOME/PATH and retry." >&2
  exit 1
}

# name / "secret" (masks input) / prompt label
REQUIRED_VARS=(
  "GATEWAY_URL:plain:Gateway base URL (https://..., or http://127.0.0.1:8080 for a same-host dev Gateway)"
  "GATEWAY_API_KEY:secret:Gateway bearer token (must equal the Gateway's WORKER_TOKEN)"
  "WORKER_ID:plain:Worker id (e.g. worker-mac-mini-01)"
  "BACKEND_ID:plain:Backend name (must match a backends.name row on the Gateway)"
  "LLAMA_MODEL:plain:llama-server model name"
)

prompt_for() {
  local var="$1" kind="$2" label="$3" value
  if [ -n "${!var:-}" ]; then
    return
  fi
  if [ "$kind" = "secret" ]; then
    read -r -s -p "$label
$var: " value
    echo
  else
    read -r -p "$label
$var: " value
  fi
  if [ -z "$value" ]; then
    echo "$var is required, aborting." >&2
    exit 1
  fi
  export "$var=$value"
}

for entry in "${REQUIRED_VARS[@]}"; do
  IFS=":" read -r var kind label <<< "$entry"
  prompt_for "$var" "$kind" "$label"
done

echo
echo "Config:"
echo "  GATEWAY_URL=$GATEWAY_URL"
echo "  GATEWAY_API_KEY=****${GATEWAY_API_KEY: -4}"
echo "  WORKER_ID=$WORKER_ID"
echo "  BACKEND_ID=$BACKEND_ID"
echo "  LLAMA_MODEL=$LLAMA_MODEL"
echo "  LLAMA_URL=${LLAMA_URL:-http://127.0.0.1:8000 (default)}"
if [ -n "${BACKEND_URL:-}" ]; then
  echo "  BACKEND_URL=$BACKEND_URL (self-registration enabled -- requires the Gateway's"
  echo "    gateway.backend.self-registration.enabled=true and a matching allowed-host-pattern)"
else
  echo "  BACKEND_URL=(not set -- self-registration disabled, register this backend via the"
  echo "    Gateway's admin API or SQL instead)"
fi
echo

JAR="target/llm-worker.jar"
if [ ! -f "$JAR" ] || [ "${1:-}" = "--rebuild" ]; then
  command -v mvn >/dev/null 2>&1 || {
    echo "mvn not found on PATH (need Maven 3.9+ to build). Install it, or place a pre-built $JAR here." >&2
    exit 1
  }
  echo "Building $JAR (mvn -q verify)..."
  mvn -q verify
fi

echo "Starting Worker..."
# -XX:-HeapDumpOnOutOfMemoryError: an OOM heap dump could otherwise persist an in-flight diff/LLM
# response to disk in plaintext (see README.md §6.1) -- the app only warns if this is missing, it
# can't enforce it from inside the JVM.
exec java -XX:-HeapDumpOnOutOfMemoryError -jar "$JAR"
