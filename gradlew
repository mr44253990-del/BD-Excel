#!/usr/bin/env sh

# Gradient wrapper shell script (minimal). In CI, the environment already provides gradle.
APP_HOME=$(cd "$(dirname "$0")" && pwd)
APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

# Use system gradle if available, otherwise download wrapper jar.
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
else
  echo "system gradle not found; please install gradle or run in CI with setup-android." >&2
  exit 1
fi
