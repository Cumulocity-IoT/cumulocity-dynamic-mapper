#!/bin/bash
#
# Copyright (c) 2025 Cumulocity GmbH.
#
# SPDX-License-Identifier: Apache-2.0
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#
#  @authors Christof Strack, Stefan Witschel
#

set -eo pipefail

TENANT_OPTIONS_CATEGORY="dynMappingService"
DEFAULT_MAPPINGS_FILE="mappings-all.json"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

function check_prerequisites() {
  local missing=()
  command -v jq >/dev/null 2>&1 || missing+=("jq")
  command -v c8y >/dev/null 2>&1 || missing+=("c8y (go-c8y-cli: https://goc8ycli.netlify.app/)")
  if [ ${#missing[@]} -ne 0 ]; then
    echo "Error: required tools are not installed:" >&2
    printf '  %s\n' "${missing[@]}" >&2
    exit 1
  fi
}

function validate_direction() {
  local dir="$1"
  if [[ "$dir" != "INBOUND" && "$dir" != "OUTBOUND" ]]; then
    echo "Error: --direction must be INBOUND or OUTBOUND (got: '$dir')" >&2
    exit 1
  fi
}

function confirm_destructive() {
  local msg="$1"
  echo "WARNING: $msg" >&2
  read -r -p "Type 'yes' to continue: " answer
  if [ "$answer" != "yes" ]; then
    echo "Aborted." >&2
    exit 1
  fi
}

function show_usage() {
  cat <<EOF
Usage: $0 <resource> <operation> [options]

MAPPINGS
  mappings list   [--direction INBOUND|OUTBOUND] [--raw]
  mappings export [--file <file>]                        Export to file as managed objects (default: $DEFAULT_MAPPINGS_FILE)
  mappings import  --format ui|mo [--file <file>]        Import from file (default: $DEFAULT_MAPPINGS_FILE)
  mappings delete [--direction INBOUND|OUTBOUND] [--force]

CONNECTORS
  connectors list   [--type <TYPE>] [--raw]
  connectors delete [--type <TYPE>] [--force]                Delete connectors, optionally filtered by connectorType
                                                          (e.g. MQTT, CUMULOCITY_MQTT_SERVICE, KAFKA, HTTP, WEB_HOOK)
  connectors reset-http [--force]                        Force-delete the default HTTP connector's tenant option
                                                          directly (bypasses the app's delete protection). It is
                                                          recreated fresh on next microservice restart.

CONFIGURATIONS
  configurations list [--raw]
  configurations delete [--force]

TEMPLATES
  templates init                                         Reset system code templates to defaults

SUBSCRIPTIONS
  subscriptions cleanup                                  Delete deprecated DynamicMapperDeviceSubscription

Options:
  --force    Skip confirmation prompt for destructive operations
EOF
}

# ---------------------------------------------------------------------------
# Mappings
# ---------------------------------------------------------------------------

function mappings_list() {
  check_prerequisites
  local direction=""
  local raw=false
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --direction) direction="$2"; shift 2 ;;
      --raw)       raw=true; shift ;;
      *) echo "Unknown option: $1" >&2; show_usage; exit 1 ;;
    esac
  done

  [ -n "$direction" ] && validate_direction "$direction"

  local raw_flag=()
  [ "$raw" = true ] && raw_flag=(--raw)

  if [ -n "$direction" ]; then
    c8y inventory find --type d11r_mapping \
      --query "d11r_mapping.direction eq '$direction'" \
      --includeAll --select name,type,d11r_mapping "${raw_flag[@]}"
  else
    c8y inventory list --type d11r_mapping --includeAll --select name,type,d11r_mapping "${raw_flag[@]}"
  fi
}

function mappings_export() {
  check_prerequisites
  local filename="$DEFAULT_MAPPINGS_FILE"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --file) filename="$2"; shift 2 ;;
      *) echo "Unknown option: $1" >&2; show_usage; exit 1 ;;
    esac
  done

  echo "Exporting mappings to '$filename'..."
  c8y inventory list --type d11r_mapping --includeAll --select name,type,d11r_mapping > "$filename"
  echo "Done — $(jq -s 'length' "$filename") mapping(s) exported."
}

function mappings_import() {
  check_prerequisites
  local format="mo"
  local filename="$DEFAULT_MAPPINGS_FILE"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --format) format="$2"; shift 2 ;;
      --file)   filename="$2"; shift 2 ;;
      *) echo "Unknown option: $1" >&2; show_usage; exit 1 ;;
    esac
  done

  if [ ! -f "$filename" ]; then
    echo "Error: file '$filename' not found." >&2
    exit 1
  fi

  echo "Importing mappings from '$filename' (format: $format)..."
  case "$format" in
    mo)
      jq -c -n '[ inputs ] | to_entries[] | {
        name: ("Mapping - " + ((.key + 1) | tostring)),
        type: "d11r_mapping",
        d11r_mapping: .value.d11r_mapping
      }' "$filename" | c8y inventory create --template "input.value"
      ;;
    ui)
      jq -c 'to_entries[] | {
        name: ("Mapping - " + ((.key + 1) | tostring)),
        type: "d11r_mapping",
        d11r_mapping: .value
      }' "$filename" | c8y inventory create --template "input.value"
      ;;
    *)
      echo "Error: unknown format '$format'. Use 'ui' or 'mo'." >&2
      exit 1
      ;;
  esac
  echo "Import complete."
}

function mappings_delete() {
  check_prerequisites
  local direction=""
  local force=false
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --direction) direction="$2"; shift 2 ;;
      --force)     force=true; shift ;;
      *) echo "Unknown option: $1" >&2; show_usage; exit 1 ;;
    esac
  done

  [ -n "$direction" ] && validate_direction "$direction"

  local scope="all mappings"
  [ -n "$direction" ] && scope="$direction mappings"
  [ "$force" = false ] && confirm_destructive "This will permanently delete $scope."

  local ids
  if [ -n "$direction" ]; then
    ids=$(c8y inventory find --type d11r_mapping \
      --query "d11r_mapping.direction eq '$direction'" \
      --includeAll --select id --output csv 2>/dev/null || true)
  else
    ids=$(c8y inventory list --type d11r_mapping \
      --includeAll --select id --output csv 2>/dev/null || true)
  fi

  if [ -z "$ids" ]; then
    echo "No $scope found."
  else
    echo "$ids" | c8y inventory delete
    echo "Deleted $scope."
  fi
}

# ---------------------------------------------------------------------------
# Connectors
# ---------------------------------------------------------------------------

function connectors_list() {
  check_prerequisites
  local type=""
  local raw=false
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --type) type="$2"; shift 2 ;;
      --raw)  raw=true; shift ;;
      *) echo "Unknown option: $1" >&2; show_usage; exit 1 ;;
    esac
  done

  local connectors
  connectors=$(c8y api --method GET --url "/service/dynamic-mapper-service/configuration/connector/instance" \
    | jq --arg type "$type" '
        (if $type == "" then . else map(select(.connectorType == $type)) end)
        | map({identifier, name, connectorType, enabled})')

  if [ "$raw" = true ]; then
    echo "$connectors"
  else
    echo "$connectors" | jq -r '
        ("IDENTIFIER\tNAME\tCONNECTOR TYPE\tENABLED"),
        (.[] | [.identifier, .name, .connectorType, (.enabled | tostring)] | @tsv)
      ' | column -t -s $'\t'
  fi
}

function connectors_delete() {
  check_prerequisites
  local type=""
  local force=false
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --type)  type="$2"; shift 2 ;;
      --force) force=true; shift ;;
      *) echo "Unknown option: $1" >&2; show_usage; exit 1 ;;
    esac
  done

  local scope="all connector configurations"
  [ -n "$type" ] && scope="connector configurations of type '$type'"
  [ "$force" = false ] && confirm_destructive "This will permanently delete $scope."

  local identifiers
  if [ -n "$type" ]; then
    identifiers=$(c8y api --method GET --url "/service/dynamic-mapper-service/configuration/connector/instance" 2>/dev/null \
      | jq -r --arg type "$type" '.[] | select(.connectorType == $type) | .identifier' || true)
  else
    identifiers=$(c8y api --method GET --url "/service/dynamic-mapper-service/configuration/connector/instance" 2>/dev/null \
      | jq -r '.[].identifier' || true)
  fi

  if [ -z "$identifiers" ]; then
    echo "No $scope found."
  else
    local id
    local success_count=0
    local fail_count=0
    while IFS= read -r id; do
      # The built-in HTTP connector is protected by the backend (400 Bad Request) and
      # gets recreated on startup anyway, so skip it instead of reporting a false success.
      if [ "$id" = "HTTP_CONNECTOR_IDENTIFIER" ]; then
        echo "Skipped connector '$id': the default HTTP connector cannot be deleted." >&2
        continue
      fi
      if c8y api --method DELETE --url "/service/dynamic-mapper-service/configuration/connector/instance/$id" >/dev/null 2>&1; then
        echo "Deleted connector '$id'."
        ((success_count++))
      else
        echo "Error: failed to delete connector '$id'." >&2
        ((fail_count++))
      fi
    done <<< "$identifiers"

    if [ "$fail_count" -gt 0 ]; then
      echo "Deleted $success_count connector(s), $fail_count failed." >&2
      exit 1
    else
      echo "Deleted $scope."
    fi
  fi
}

function connectors_reset_http() {
  check_prerequisites
  local force=false
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --force) force=true; shift ;;
      *) echo "Unknown option: $1" >&2; show_usage; exit 1 ;;
    esac
  done

  [ "$force" = false ] && confirm_destructive "This will delete the default HTTP connector's tenant option directly. It is recreated fresh on the next microservice restart."

  # Bypasses the app's delete protection (400 "Can't delete a HttpConnector!") by
  # removing the underlying tenant option directly, e.g. to clear a stale/incorrect path.
  c8y tenantoptions delete --category dynMappingService \
    --key credentials.connection.configuration.HTTP_CONNECTOR_IDENTIFIER --force
  echo "Deleted tenant option for the default HTTP connector. Restart the microservice to recreate it."
}

# ---------------------------------------------------------------------------
# Configurations
# ---------------------------------------------------------------------------

function configurations_list() {
  check_prerequisites
  local raw=false
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --raw) raw=true; shift ;;
      *) echo "Unknown option: $1" >&2; show_usage; exit 1 ;;
    esac
  done

  local configs
  configs=$(c8y tenantoptions getForCategory --category "$TENANT_OPTIONS_CATEGORY" --raw \
    | jq 'with_entries(select(.key | startswith("service.configuration")))')

  if [ "$raw" = true ]; then
    echo "$configs"
  else
    echo "$configs" | jq -r '
        ("KEY\tVALUE"),
        (to_entries[] | [.key, (.value | if length > 80 then .[0:80] + "..." else . end)] | @tsv)
      ' | column -t -s $'\t'
  fi
}

function configurations_delete() {
  check_prerequisites
  local force=false
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --force) force=true; shift ;;
      *) echo "Unknown option: $1" >&2; show_usage; exit 1 ;;
    esac
  done

  [ "$force" = false ] && confirm_destructive "This will permanently delete all service configurations."

  local keys
  keys=$(c8y tenantoptions getForCategory --category "$TENANT_OPTIONS_CATEGORY" --raw 2>/dev/null \
    | jq -r 'keys[] | select(startswith("service.configuration"))' || true)

  if [ -z "$keys" ]; then
    echo "No service configurations found."
  else
    echo "$keys" \
      | jq -Rc '{key: .}' \
      | c8y tenantoptions delete --category "$TENANT_OPTIONS_CATEGORY" --key -.key
    echo "Configurations deleted."
  fi
}

# ---------------------------------------------------------------------------
# Templates
# ---------------------------------------------------------------------------

function templates_init() {
  check_prerequisites
  echo "Initializing system code templates to default values..."
  c8y api \
    --method POST \
    --url "/service/dynamic-mapper-service/operation" \
    --data '{"operation": "INIT_CODE_TEMPLATES"}'
  echo "System code templates initialized."
}

# ---------------------------------------------------------------------------
# Subscriptions
# ---------------------------------------------------------------------------

function subscriptions_cleanup() {
  check_prerequisites
  echo "Deleting deprecated subscription 'DynamicMapperDeviceSubscription'..."
  echo "(Renamed to 'DynamicMapperStaticDeviceSubscription')"
  c8y notification2 subscriptions list --subscription DynamicMapperDeviceSubscription \
    | c8y notification2 subscriptions delete
  echo "Done."
}

# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if [ $# -lt 2 ]; then
  show_usage
  exit 1
fi

RESOURCE="$1"
OPERATION="$2"
shift 2

FUNCTION_NAME="${RESOURCE}_${OPERATION//-/_}"

if declare -f "$FUNCTION_NAME" >/dev/null 2>&1; then
  "$FUNCTION_NAME" "$@"
else
  echo "Error: unknown command '$RESOURCE $OPERATION'." >&2
  echo "" >&2
  show_usage
  exit 1
fi
