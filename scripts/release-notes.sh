#!/bin/sh
# Prints the CHANGELOG.md section of one version (without its heading), for the release page.
# Usage: scripts/release-notes.sh <version>   e.g. scripts/release-notes.sh 0.1.0
set -eu
VERSION=${1:?usage: release-notes.sh <version>}
NOTES=$(awk -v v="$VERSION" '
  index($0, "## [" v "]") == 1 { on = 1; next }
  /^## \[/ || /^\[[^]]+\]: / { on = 0 }
  on' CHANGELOG.md)
if [ -z "$(printf '%s' "$NOTES" | tr -d '[:space:]')" ]; then
  echo "CHANGELOG.md has no section for $VERSION" >&2
  exit 1
fi
printf '%s\n' "$NOTES"
