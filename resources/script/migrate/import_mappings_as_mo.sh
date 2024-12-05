#!/bin/sh
set -e

###
# script to import mappings
### 
cat mappings-OLD-all.json | jq -c 'to_entries[] | {
  name: ("Mapping - " + ((.key + 1) | tostring)),
  type: "d11r_mapping",
  d11r_mapping: .value
}'| c8y inventory create --template "input.value"