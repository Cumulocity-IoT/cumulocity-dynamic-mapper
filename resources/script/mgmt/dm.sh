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
  mappings list   [--direction INBOUND|OUTBOUND]
  mappings export [--file <file>]                        Export to file as managed objects (default: $DEFAULT_MAPPINGS_FILE)
  mappings import  --format ui|mo [--file <file>]        Import from file (default: $DEFAULT_MAPPINGS_FILE)
  mappings delete [--direction INBOUND|OUTBOUND] [--force]

CONNECTORS
  connectors list
  connectors delete [--force]

CONFIGURATIONS
  configurations list
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
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --direction) direction="$2"; shift 2 ;;
      *) echo "Unknown option: $1" >&2; show_usage; exit 1 ;;
    esac
  done

  if [ -n "$direction" ]; then
    c8y inventory find --type d11r_mapping \
      --query "d11r_mapping.direction eq '$direction'" \
      --includeAll --select name,type,d11r_mapping
  else
    c8y inventory list --type d11r_mapping --includeAll --select name,type,d11r_mapping
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

  local scope="all mappings"
  [ -n "$direction" ] && scope="$direction mappings"
  [ "$force" = false ] && confirm_destructive "This will permanently delete $scope."

  if [ -n "$direction" ]; then
    c8y inventory find --type d11r_mapping \
      --query "d11r_mapping.direction eq '$direction'" \
      --includeAll | c8y inventory delete
  else
    c8y inventory list --type d11r_mapping --includeAll | c8y inventory delete
  fi
  echo "Deleted $scope."
}

# ---------------------------------------------------------------------------
# Connectors
# ---------------------------------------------------------------------------

function connectors_list() {
  check_prerequisites
  c8y tenantoptions getForCategory --category "$TENANT_OPTIONS_CATEGORY" --raw \
    | jq 'with_entries(select(.key | startswith("credentials.connection.")))'
}

function connectors_delete() {
  check_prerequisites
  local force=false
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --force) force=true; shift ;;
      *) echo "Unknown option: $1" >&2; show_usage; exit 1 ;;
    esac
  done

  [ "$force" = false ] && confirm_destructive "This will permanently delete all connector configurations."

  c8y tenantoptions getForCategory --category "$TENANT_OPTIONS_CATEGORY" \
    | jq 'keys[] | select(startswith("credentials.connection.")) | {key: .}' \
    | c8y tenantoptions delete --category "$TENANT_OPTIONS_CATEGORY" --key -.key
  echo "Connectors deleted."
}

# ---------------------------------------------------------------------------
# Configurations
# ---------------------------------------------------------------------------

function configurations_list() {
  check_prerequisites
  c8y tenantoptions getForCategory --category "$TENANT_OPTIONS_CATEGORY" --raw \
    | jq 'with_entries(select(.key | startswith("service.configuration")))'
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

  c8y tenantoptions getForCategory --category "$TENANT_OPTIONS_CATEGORY" \
    | jq 'keys[] | select(startswith("service.configuration")) | {key: .}' \
    | c8y tenantoptions delete --category "$TENANT_OPTIONS_CATEGORY" --key -.key
  echo "Configurations deleted."
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
