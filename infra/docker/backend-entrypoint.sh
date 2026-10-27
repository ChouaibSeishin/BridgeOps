#!/bin/sh
set -eu
if [ "$(id -u)" -eq 0 ]; then
  printf '%s\n' 'BridgeOps requires a non-root runtime identity.' >&2
  exit 78
fi
exec java -XX:MaxRAMPercentage=75 -jar /app/app.jar "$@"
