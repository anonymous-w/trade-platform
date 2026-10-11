#!/usr/bin/env bash
# Installs SYN26 fixture data and generated demo PDFs in a Docker Compose DEV environment.
set -euo pipefail
cd "$(dirname "$0")/../.."
if [[ ! -f .env ]]; then
  echo 'Missing .env. Copy .env.example, set strong passwords and DEMO_DATA_ENABLED=true.' >&2
  exit 1
fi
if ! grep -Eqi '^DEMO_DATA_ENABLED=true[[:space:]]*$' .env; then
  echo 'Refusing to seed: .env must explicitly contain DEMO_DATA_ENABLED=true.' >&2
  echo 'Never run this against a production database.' >&2
  exit 1
fi

echo '[1/4] Verify the containers have started'
docker compose exec -T db sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
docker compose exec -T backend sh -c 'test -d /data/documents'
echo '[2/4] Copy synthetic PDFs to persistent document storage'
docker compose cp samples/synthetic-demo/documents/. backend:/data/documents/
echo '[3/4] Copy synthetic SQL seed into database container'
docker compose cp samples/synthetic-demo/seed_demo.sql db:/tmp/synthetic_seed.sql
echo '[4/4] Apply SQL in one transaction; existing data are not modified'
docker compose exec -T db sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /tmp/synthetic_seed.sql'
echo 'Done! Open http://localhost:8088 and search SPG (model field), or SYN26 in Invoice No.'
