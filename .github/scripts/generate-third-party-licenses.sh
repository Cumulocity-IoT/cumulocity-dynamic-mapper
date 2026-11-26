#!/usr/bin/env bash
# Regenerates THIRD-PARTY-LICENSES.md and fails if a dependency has no acceptable license.
#
# Runs the Maven license plugin over the backend and license-checker over the UI production
# dependencies, then hands both reports to third-party-licenses.py. The UI dependencies must be
# installed first (npm ci in dynamic-mapper-ui).
#
# Usage: generate-third-party-licenses.sh [version]
#   version  optional, passed to Maven as -Drevision (the release workflow passes the tag version)
set -euo pipefail

cd "$(dirname "$0")/../.."
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

REVISION=()
[ -n "${1:-}" ] && REVISION=("-Drevision=$1")

[ -d dynamic-mapper-ui/node_modules ] || { echo "::error::run 'npm ci' in dynamic-mapper-ui first" >&2; exit 1; }

echo "Scanning backend dependencies"
mvn -B -q -pl dynamic-mapper-service -am ${REVISION[@]+"${REVISION[@]}"} \
  org.codehaus.mojo:license-maven-plugin:aggregate-download-licenses \
  -DlicensesOutputDirectory="$WORK/java" \
  -DlicensesOutputFile="$WORK/java/licenses.xml" \
  -DincludedScopes=compile,runtime

echo "Scanning UI dependencies"
(cd dynamic-mapper-ui && npx --yes license-checker-rseidelsohn@4.4.2 --production --json --out "$WORK/npm.json" >/dev/null)

python3 -I .github/scripts/third-party-licenses.py "$WORK/java/licenses.xml" "$WORK/npm.json" THIRD-PARTY-LICENSES.md
