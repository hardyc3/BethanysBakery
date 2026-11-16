#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
docker compose -f compose.dev.yml --env-file .env.example up --build "$@"
