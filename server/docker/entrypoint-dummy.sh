#!/bin/sh
set -eu

export SPRING_PROFILES_ACTIVE=dummy
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"

exec java -jar /app/app.jar "$@"
