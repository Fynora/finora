#!/usr/bin/env bash
# Regenerates backend/openapi/openapi.json from a real, booted instance of the backend.
#
# Why a boot-and-curl script instead of springdoc-openapi-maven-plugin: this repo already hit a
# real springdoc outage once (see the version-pin comment on the springdoc dependency in pom.xml --
# 2.6.0 silently 500'd on every /v3/api-docs request after a Spring Boot minor bump, and it was
# only caught by asking the running app directly). A dedicated codegen plugin is one more
# separately-versioned dependency that can break the same way; this script instead reuses the
# exact boot pattern ci.yml's e2e/smoke jobs already use (SPRING_PROFILES_ACTIVE=dev, curl,
# tear down), so there's nothing new to keep pinned in step with the Spring Framework line.
#
# Needs a reachable Postgres (see docker-compose.yml for the expected finora/finora/finora
# credentials on 5432) -- this script does not start one itself, on purpose: every other
# backend-boot step in this repo assumes Postgres is already up, and duplicating that lifecycle
# management here would be one more thing to keep in sync with docker-compose.yml.
set -euo pipefail
cd "$(dirname "$0")/.."

PORT="${OPENAPI_GEN_PORT:-8098}"
OUT="openapi/openapi.json"

JAR=$(ls target/*.jar 2>/dev/null | grep -v original | head -1 || true)
if [ -z "$JAR" ]; then
  echo "No backend jar found in target/ -- run 'mvn -DskipTests package' first." >&2
  exit 1
fi

SPRING_PROFILES_ACTIVE=dev PORT="$PORT" java -jar "$JAR" &
PID=$!
trap 'kill "$PID" 2>/dev/null || true' EXIT

READY=""
for _ in $(seq 1 60); do
  if curl -sf "http://localhost:$PORT/actuator/health" > /dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 2
done
if [ -z "$READY" ]; then
  echo "Backend never became healthy on port $PORT within 120s." >&2
  exit 1
fi

# -f: fail loudly (not silently write an HTML error page) on anything but 2xx -- this is the
# specific guard against the historical failure mode above, where every request quietly 500'd.
curl -sf "http://localhost:$PORT/v3/api-docs" | python3 -m json.tool > "$OUT"

python3 - "$OUT" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    spec = json.load(f)
paths = len(spec.get("paths", {}))
schemas = len(spec.get("components", {}).get("schemas", {}))
if paths == 0 or schemas == 0:
    print(f"Generated spec looks empty (paths={paths}, schemas={schemas}) -- refusing to trust it.", file=sys.stderr)
    sys.exit(1)
print(f"Wrote {sys.argv[1]}: {paths} paths, {schemas} schemas.")
PY
