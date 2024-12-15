#!/bin/bash
set -e

###
# script migrates the mappings form a format used in releases up to 4.6.1 to the new format used from 4.7.x onwards
# changes are:
## step 1 change format
# 1. rename 'source' -> 'sourceTemplate'
# 2. rename 'target' -> 'targetTemplate'
# 3. rename 'filterOutbound' -> 'filterMapping' only for outbound mappings
# 4. remove 'subscriptionTopic', instead the conent of 'mappingTopic' is used for managing the subscriptions
# 5. rename 'extension.event' -> 'extension.eventName'
# 6. rename 'extension.name' -> 'extension.extensionName'
# 6. add 'extension.extensionType: 'PROCESSOR_EXTENSION_SOURCE'
# 7. remove 'extension.loaded'
# 8. rename 'mapDeviceIdentifier' -> 'useExternalId'

## step 2 change the used identifiers in the substitutions
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
  echo "Usage: $0 [migrateMappings|deleteMappings|deleteConnectors|importMappingsAsMO|exportMappingsAsMO]"
  echo "  migrateMappings:     Migrate mappings from pre 4.7 format to new format"
  echo "  deleteMappings:      Delete mappings"
  echo "  deleteConnectors:    Delete connectors"
  echo "  importMappingsAsMO   [filename]: Import mappings from file as managedObjects"
  echo "  exportMappingsAsMO:  [filename]: Export mappings to file as managedObjects"
  echo ""
  echo "If filename is not provided, defaults to 'mappings-all.json'"

  check_prerequisites
}

function import_mappings_as_mo() {
  local missing_programs=()
  local filename="${1:-mappings-all.json}" # Default to mappings-all.json if no argument provided

  if [ ! -f "$filename" ]; then
    echo "Error: File '$filename' not found!"
    exit 1
  fi

  cat "$filename" | jq -c -n '
  [ inputs ] | to_entries[] | {
    name: ("Mapping - " + ((.key + 1) | tostring)),
    type: "d11r_mapping",
    d11r_mapping: .value.d11r_mapping
  }
'  | c8y inventory create --template "input.value"
}

function export_mappings_as_mo() {
  local missing_programs=()
  local filename="${1:-mappings-all.json}" # Default to mappings-all.json if no argument provided

    c8y inventory list --type d11r_mapping --includeAll --select name,type,d11r_mapping > "$filename" 
}

function migrate_mappings() {
  check_prerequisites
  # step 0 save existing mappings to file ORIGINAL_MAPPINGS and only use the attribute d11r_mapping
  echo 'step 0 save existing mappings to file ORIGINAL_MAPPINGS and only use the attribute d11r_mapping'
  c8y inventory list --type d11r_mapping --includeAll | jq -s 'map(.d11r_mapping)' >${ORIGINAL_MAPPINGS_NAME}.json

  # step 1 transform mappings to the new format except the substitutions
  echo 'step 1 transform mappings to the new format except the substitutions'
  cat ${ORIGINAL_MAPPINGS_NAME}.json | jq '[.[] | 
  . + {
    identifier: .ident,
    useExternalId: .mapDeviceIdentifier, 
    sourceTemplate: .source,
    targetTemplate: .target,
    active: false,
  } + (if .direction == "OUTBOUND" then {
    filterMapping: .filterOutbound  
  } else {} end) + (if .extension then {
    extension: (.extension + {
      eventName: .extension.event,
      extensionName: .extension.name,
      extensionType: "PROCESSOR_EXTENSION_SOURCE"
    } | del(.event, .name, .loaded)),
    "mappingType": "PROCESSOR_EXTENSION_SOURCE"
  } else {} end)
  | del(.source, .target, .filterOutbound, .subscriptionTopic, .ident, .mapDeviceIdentifier)
]' >"${MIGRATED_MAPPINGS_name}-step1.json"

  # step 2a transform substitutions for OUTBOUND to the new format
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
      end
    ))
  } else {} end)
]' >"${MIGRATED_MAPPINGS_name}-step2.json"

  # step 2b transform substitutions for INBOUND to the new format
  echo 'step 2b transform substitutions for INBOUND to the new format'
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
      end
    ))
  } else {} end)
]' >"${MIGRATED_MAPPINGS_name}-final.json"

  # step 3 delete old mappings from tenant
  echo 'step 3 delete old mappings from tenant'
  c8y inventory list --type d11r_mapping --includeAll | c8y inventory delete

  # step 4 create transformed mappings
  echo 'step 4 create transformed mappings'
  cat ${MIGRATED_MAPPINGS_name}-final.json | jq -c 'to_entries[] | {
  name: .value.name,
  type: "d11r_mapping",
  d11r_mapping: .value
}' | c8y inventory create --template "input.value"

}

function delete_mappings() {
  check_prerequisites
  echo 'delete mappings'
  c8y inventory list --includeAll --type d11r_mapping | c8y inventory delete
}

function delete_connectors() {
  check_prerequisites
  echo 'delete connectors'
  c8y tenantoptions getForCategory --category dynamic.mapper.service | jq 'keys| .[] | select(startswith("credentials.connection.configuration"))' -r | c8y tenantoptions delete --category dynamic.mapper.service --key -.key
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

# Check if argument is provided
if [ $# -ne 1 ]; then
  show_usage
  exit 1
fi

# Process argument
case "$1" in
migrateMappings)
  migrate_mappings
  ;;
deleteMappings)
  delete_mappings
  ;;
deleteConnectors)
  delete_connectors
  ;;
importMappingsAsMO)
  import_mappings_as_mo "${2:-}"
  ;;
exportMappingsAsMO)
  export_mappings_as_mo "${2:-}"
  ;;
*)
  show_usage
  exit 1
  ;;
esac