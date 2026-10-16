#!/usr/bin/env bash
set -euo pipefail

IMAGE="${SMARTOFFICE_IMAGE:-smartoffice-backend:1.0.0}"
ENV_FILE="${SMARTOFFICE_ENV_FILE:-deploy/.env}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE. Copy deploy/.env.example to deploy/.env and fill real values." >&2
  exit 1
fi

docker rm -f smartoffice-backend >/dev/null 2>&1 || true

docker run -d \
  --name smartoffice-backend \
  --restart unless-stopped \
  --network host \
  --env-file "$ENV_FILE" \
  "$IMAGE"

echo "smartoffice-backend started with host networking"
