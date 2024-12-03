#!/bin/sh
set -e

###
# script migrates the mappings form a format used in releases up to 4.6.1 to the new format used from 4.7.x onwards
# changes are:
# 1. rename 'source' -> 'sourceTemplate'
# 2. rename 'target' -> 'targetTemplate'
# 3. rename 'filterOutbound' -> 'filterMapping' only for outbound mappings
# 4. remove 'subscriptionTopic', instead the conent of 'mappingTopic' is used for managing the subscriptions
# 5. rename 'extension.event' -> 'extension.eventName'
# 6. rename 'extension.name' -> 'extension.extensionName'
# 6. add 'extension.extensionType: 'PROCESSOR_EXTENSION_SOURCE'
# 6. remove 'extension.loaded'
# this can be used after a migration to a new version of the dynamic mapper. in case the structure of the mappings has changed and become invalid
###

ORIGINAL_MAPPINGS="mappings-v46x.json"
_ORIGINAL_MAPPINGS="_mappings-v46x.json"
MIGRATED_MAPPINGS="mappings-v47x.json"

# step 1 save existing mappings to file ORIGINAL_MAPPINGS
c8y inventory list --type d11r_mapping --includeAll | jq -s '.' >$ORIGINAL_MAPPINGS
# step 2 transform mappings to the new format
cat $_ORIGINAL_MAPPINGS | jq '[.[] | 
  . + {
    sourceTemplate: .source,
    targetTemplate: .target
    } + (if .direction == "OUTBOUND" then {
    filterMapping: .filterOutbound  
    } else  {} end) + (if .extension then {
        extension: (.extension + {
          eventName: .extension.event,
          extensionName: .extension.name,
          extensionType: "PROCESSOR_EXTENSION_SOURCE"
        } | del(.event, .name, .loaded))
      } else {} end)
   | del(.source, .target, .filterOutbound, .subscriptionTopic)
]' >$MIGRATED_MAPPINGS

# step 3 delete old mappings
c8y inventory list --type d11r_mapping | c8y inventory delete

# step 4 create transformed mappings
cat $MIGRATED_MAPPINGS | jq -c '.[]'| c8y inventory create 
