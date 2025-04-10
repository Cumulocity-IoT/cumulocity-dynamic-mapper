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
#

set -e

###
# script migrates the mappings form a format used in releases up to 4.6.1 to the new format used from 4.7.x onwards
# changes are:
## step 1 change format
# 1. rename 'source' -> 'sourceTemplate'
# 2. rename 'target' -> 'targetTemplate'
# 3. rename 'filterOutbound' -> 'filterMapping' only for outbound mappings
# 4. remove 'subscriptionTopic', instead the content of 'mappingTopic' is used for managing the subscriptions
# 5. rename 'extension.event' -> 'extension.eventName'
# 6. rename 'extension.name' -> 'extension.extensionName'
# 6. add 'extension.extensionType: 'EXTENSION_SOURCE'
# 7. remove 'extension.loaded'
# 8. rename 'mapDeviceIdentifier' -> 'useExternalId'

## Step 2 change the used identifiers in the substitutions
# 1. for mappings with targetAPI EVENT, ALARM, or MEASUREMENT, changes "source.id" to "_IDENTITY_.externalId"
# 2. for mappings with targetAPI OPERATION, changes "deviceId" to "_IDENTITY_.externalId"
# 3. for mappings with targetAPI INVENTORY, changes "id" to "_IDENTITY_.externalId"
# 4. remove 'resolve2ExternalId'
#

# this can be used after a migration to a new version of the dynamic mapper. in case the structure of the mappings has changed and become invalid
###

ORIGINAL_MAPPINGS_NAME="mappings-v46"
MIGRATED_MAPPINGS_name="mappings-v47"

function show_usage() {
  echo "Usage: $0 [RESOURCE OPERATION] [OPTIONS]"
  echo ""
  echo "Available commands (resource operation):"

  echo "  mappings delete --direction [INBOUND|OUTBOUND]      : Delete mappings for a specified direction INBOUND, OUTBOUND, if not specified delete all"
  echo "  mappings import --format [ui|mo] --file [filename]  : Import mappings from UI export file or as managedObjects"
  echo "  mappings export --file [filename]                   : Export mappings to file as managedObjects"
  echo "  mappings list --direction [INBOUND|OUTBOUND]        : Mappings list"
  echo "  connectors delete                                   : Delete connectors"
  echo "  connectors list                                     : List connectors"
  echo "  configurations delete                               : Delete service configurations"
  echo "  configurations list                                 : List service configurations"
  echo "  mappings migrate:    Migrate mappings from pre 4.7 format to new format. The migration consists of the following steps:"
  echo "     Step 0 save existing mappings to file ORIGINAL_MAPPINGS and only use the attribute d11r_mapping"
  echo "     Step 1 transform mappings to the new format except the substitutions"
  echo "     Step 2a transform substitutions for OUTBOUND to the new format"
  echo "     Step 2b transform substitutions for INBOUND to the new format"
  echo "     Step 3 delete old mappings from tenant"
  echo "     Step 4 create transformed mappings in tenant"
  echo ""
  echo "If filename is not provided, defaults to 'mappings-all.json'"

  check_prerequisites
}

function mappings_import() {
  check_prerequisites
  
  # Parse options
  local format="mo" # default format
  local filename="mappings-all.json" # default filename
  
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --format)
        format="$2"
        shift 2
        ;;
      --file)
        filename="$2"
        shift 2
        ;;
      *)
        echo "Unknown option: $1"
        show_usage
        exit 1
        ;;
    esac
  done

  if [ ! -f "$filename" ]; then
    echo "Error: File '$filename' not found!"
    exit 1
  fi

  echo "Importing mappings from file '$filename' in format '$format'"
  
  case "$format" in
    mo)
      # Import as managed objects
      cat "$filename" | jq -c -n '
      [ inputs ] | to_entries[] | {
        name: ("Mapping - " + ((.key + 1) | tostring)),
        type: "d11r_mapping",
        d11r_mapping: .value.d11r_mapping
      }' | c8y inventory create --template "input.value"
      ;;
    ui)
      # Import from UI export format
      cat "$filename" | jq -c 'to_entries[] | {
        name: ("Mapping - " + ((.key + 1) | tostring)),
        type: "d11r_mapping",
        d11r_mapping: .value
      }' | c8y inventory create --template "input.value"
      ;;
    *)
      echo "Error: Unknown format '$format'. Use 'ui' or 'mo'."
      exit 1
      ;;
  esac
}

function mappings_export() {
  check_prerequisites
  
  # Parse options
  local filename="mappings-all.json" # default filename
  
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --file)
        filename="$2"
        shift 2
        ;;
      *)
        echo "Unknown option: $1"
        show_usage
        exit 1
        ;;
    esac
  done

  echo "Exporting mappings to file '$filename'"
  c8y inventory list --type d11r_mapping --includeAll --select name,type,d11r_mapping > "$filename"
  echo "Mappings exported successfully to '$filename'"
}

function mappings_list() {
  check_prerequisites
  
  # Parse options
  local direction="" # default: all directions
  
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --direction)
        direction="$2"
        shift 2
        ;;
      *)
        echo "Unknown option: $1"
        show_usage
        exit 1
        ;;
    esac
  done

  if [ -n "$direction" ]; then
    echo "Listing mappings with direction '$direction'"
    c8y inventory find --type d11r_mapping --query "d11r_mapping.direction eq '$direction'" --includeAll --select name,type,d11r_mapping
  else
    echo "Listing all mappings"
    c8y inventory list --type d11r_mapping --includeAll --select name,type,d11r_mapping
  fi
}

function mappings_migrate() {
  check_prerequisites
  # Step 0 save existing mappings to file ORIGINAL_MAPPINGS and only use the attribute d11r_mapping
  echo 'Step 0 save existing mappings to file ORIGINAL_MAPPINGS and only use the attribute d11r_mapping'
  c8y inventory list --type d11r_mapping --includeAll | jq -s 'map(.d11r_mapping)' >${ORIGINAL_MAPPINGS_NAME}.json

  # Step 1 transform mappings to the new format except the substitutions
  echo 'Step 1 transform mappings to the new format except the substitutions'
  cat ${ORIGINAL_MAPPINGS_NAME}.json | jq '[.[] | 
  . + {
    identifier: .ident,
    useExternalId: .mapDeviceIdentifier,
    mappingTopic: .subscriptionTopic,
    mappingTopicSample: .templateTopicSample
    sourceTemplate: .source,
    targetTemplate: .target,
    active: false,
  } + (if .direction == "OUTBOUND" then {
    filterMapping: .filterOutbound  
  } else {} end) + (if .extension then {
    extension: (.extension + {
      eventName: .extension.event,
      extensionName: .extension.name,
      extensionType: "EXTENSION_SOURCE"
    } | del(.event, .name, .loaded)),
    "mappingType": "EXTENSION_SOURCE"
  } else {} end)
  | del(.source, .target, .filterOutbound, .subscriptionTopic, , .templateTopicSample, .templateTopic, .ident, .mapDeviceIdentifier)
]' >"${MIGRATED_MAPPINGS_name}-step1.json"

  # Step 2a transform substitutions for OUTBOUND to the new format
  echo '2a transform substitutions for OUTBOUND to the new format'
  cat ${MIGRATED_MAPPINGS_name}-step1.json | jq '[.[] | 
  . as $parent |
  . + (if .direction == "OUTBOUND" then {
          substitutions: (.substitutions | map(
      del(.resolve2ExternalId) |
      if (.pathSource == "source.id" and (($parent.targetAPI == "EVENT") or ($parent.targetAPI == "ALARM") or ($parent.targetAPI == "MEASUREMENT"))) then
        . + {pathSource: "_IDENTITY_.externalId"}
      elif (.pathSource == "deviceId" and (($parent.targetAPI == "OPERATION") )) then
        . + {pathSource: "_IDENTITY_.externalId"}
      elif (.pathSource == "id" and (($parent.targetAPI == "INVENTORY") )) then
        . + {pathSource: "_IDENTITY_.externalId"}
      else
        .
      end |
      if (.repairStrategy == "REMOVE_IF_MISSING" or .repairStrategy == "REMOVE_IF_NULL") then
        . + {repairStrategy: "REMOVE_IF_MISSING_OR_NULL"}
      else
        .
      end
    ))
  } else {} end)
]' >"${MIGRATED_MAPPINGS_name}-step2.json"

  # Step 2b transform substitutions for INBOUND to the new format
  echo 'Step 2b transform substitutions for INBOUND to the new format'
  cat ${MIGRATED_MAPPINGS_name}-step2.json | jq '[.[] | 
  . as $parent |
  . + (if .direction == "INBOUND" then {
          substitutions: (.substitutions | map(
      del(.resolve2ExternalId) |
      if (.pathTarget == "source.id" and (($parent.targetAPI == "EVENT") or ($parent.targetAPI == "ALARM") or ($parent.targetAPI == "MEASUREMENT"))) then
        . + {pathTarget: "_IDENTITY_.externalId"}
      elif (.pathTarget == "deviceId" and (($parent.targetAPI == "OPERATION") )) then
        . + {pathTarget: "_IDENTITY_.externalId"}
      elif (.pathTarget == "id" and (($parent.targetAPI == "INVENTORY") )) then
        . + {pathTarget: "_IDENTITY_.externalId"}
      else
        .
      end |
      if (.repairStrategy == "REMOVE_IF_MISSING" or .repairStrategy == "REMOVE_IF_NULL") then
        . + {repairStrategy: "REMOVE_IF_MISSING_OR_NULL"}
      else
        .
      end
    ))
  } else {} end)
]' >"${MIGRATED_MAPPINGS_name}-final.json"

  # Step 3 delete old mappings from tenant
  echo 'Step 3 delete old mappings from tenant'
  c8y inventory list --type d11r_mapping --includeAll | c8y inventory delete

  # Step 4 create transformed mappings in tenant
  echo 'Step 4 create transformed mappings in tenant'
  cat ${MIGRATED_MAPPINGS_name}-final.json | jq -c 'to_entries[] | {
  name: .value.name,
  type: "d11r_mapping",
  d11r_mapping: .value
}' | c8y inventory create --template "input.value"
}

function mappings_delete() {
  check_prerequisites
  
  # Parse options
  local direction="" # default: all directions
  
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --direction)
        direction="$2"
        shift 2
        ;;
      *)
        echo "Unknown option: $1"
        show_usage
        exit 1
        ;;
    esac
  done

  if [ -n "$direction" ]; then
    echo "Deleting mappings with direction '$direction'"
    c8y inventory find --type d11r_mapping --query "d11r_mapping.direction eq '$direction'" --includeAll | c8y inventory delete
  else
    echo "Deleting all mappings"
    c8y inventory list --includeAll --type d11r_mapping | c8y inventory delete
  fi
}

function connectors_delete() {
  check_prerequisites
  echo 'Delete connectors'
  c8y tenantoptions getForCategory --category dynamic.mapper.service | jq 'keys| .[] | select(startswith("credentials.connection.configuration"))' -r | c8y tenantoptions delete --category dynamic.mapper.service --key -.key
}

function connectors_list() {
  check_prerequisites
  echo 'List connectors'
  c8y tenantoptions getForCategory --category dynamic.mapper.service --raw | jq 'with_entries(select(.key | startswith("credentials.connection.")))'
}

function configurations_delete() {
  check_prerequisites
  echo 'Delete configurations'
  c8y tenantoptions getForCategory --category dynamic.mapper.service | jq 'keys| .[] | select(startswith("service.configuration"))' -r | c8y tenantoptions delete --category dynamic.mapper.service --key -.key
}

function configurations_list() {
  check_prerequisites
  echo 'List configurations'
  c8y tenantoptions getForCategory --category dynamic.mapper.service --raw | jq 'with_entries(select(.key | startswith("service.configuration")))'
}

function check_prerequisites() {
  local missing_programs=()

  # Check for jq
  if ! command -v jq >/dev/null 2>&1; then
    missing_programs+=("jq")
  fi

  # Check for c8y
  if ! command -v c8y >/dev/null 2>&1; then
    missing_programs+=("c8y")
  fi

  # If any programs are missing, print error message and exit
  if [ ${#missing_programs[@]} -ne 0 ]; then
    echo "Error: Required programs are not installed:"
    printf '%s\n' "${missing_programs[@]}"
    echo "Please install the missing programs and try again, for go c8y cli see https://goc8ycli.netlify.app/"
    exit 1
  fi
}

# Check if arguments are provided
if [ $# -lt 2 ]; then
  show_usage
  exit 1
fi

# Process arguments using the resource operation pattern
RESOURCE="$1"
OPERATION="$2"
shift 2  # Remove the first two arguments

# Create the function name based on resource and operation
FUNCTION_NAME="${RESOURCE}_${OPERATION//-/_}"

# Check if the function exists and call it with remaining arguments
if type "$FUNCTION_NAME" &>/dev/null; then
  "$FUNCTION_NAME" "$@"  # Pass all remaining arguments to the function
else
  show_usage
  exit 1
fi