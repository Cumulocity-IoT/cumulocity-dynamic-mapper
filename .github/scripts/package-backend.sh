#!/usr/bin/env bash
# Builds the release variants of the backend microservice package.
#
# Every variant is the Maven-built dynamic-mapper-service.zip with its cumulocity.json manifest
# replaced by one of the manifests in src/main/configuration. The manifests differ by resource size
# (starter/standard/performance/max) and by whether the Cumulocity MQTT Service is required.
#
# Usage: package-backend.sh <version> [service-dir]
#   version      substituted for @project.version@ in the manifests
#   service-dir  defaults to dynamic-mapper-service
set -euo pipefail

VERSION="${1:?usage: $0 <version> [service-dir]}"
SERVICE_DIR="${2:-dynamic-mapper-service}"
TARGET="$SERVICE_DIR/target"
CONFIG="$SERVICE_DIR/src/main/configuration"
BASE="$TARGET/dynamic-mapper-service.zip"

[ -f "$BASE" ] || { echo "::error::$BASE not found - run the Maven package first" >&2; exit 1; }

# size name -> manifest file name suffix
suffix() {
  case "$1" in
    starter) echo "" ;;
    standard) echo "-2c" ;;
    performance) echo "-8c" ;;
    max) echo "-16c" ;;
  esac
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Writes <target>/dynamic-mapper-service-<size><variant>.zip, where manifest is <config>/cumulocity<suffix><mqtt>.json
package() {
  local size="$1" mqtt="$2" out="$3"
  local manifest="$CONFIG/cumulocity$(suffix "$size")${mqtt}.json"

  # The plain starter package is the Maven output as built; its manifest is already the right one.
  if [ "$size" = starter ] && [ -z "$mqtt" ]; then
    cp "$BASE" "$out"
    echo "packaged $out <- (unchanged)"
    return
  fi

  [ -f "$manifest" ] || { echo "::error::manifest $manifest not found" >&2; exit 1; }

  cp "$BASE" "$out"
  # zip stores the entry under its file name; it has to be named cumulocity.json to replace the one in the package
  mkdir -p "$WORK/$size$mqtt"
  sed "s/@project.version@/$VERSION/g" "$manifest" > "$WORK/$size$mqtt/cumulocity.json"
  zip -q -j "$out" "$WORK/$size$mqtt/cumulocity.json"
  echo "packaged $out <- $(basename "$manifest")"
}

for size in starter standard performance max; do
  package "$size" ""                 "$TARGET/dynamic-mapper-service-$size.zip"
  package "$size" "-withoutMQTTService" "$TARGET/dynamic-mapper-service-$size-withoutMQTTService.zip"
done
