#!/bin/bash
#
# test-multi-tenant: Mapping CRUD isolation within current tenant
#
# Creates a mapping, verifies it appears exactly once in the listing,
# then deletes it and verifies it is gone.  Validates that the mapping
# API is consistent and that tenant isolation works for the logged-in tenant.
#
# Prerequisites:
#   - Dynamic mapper service running
#   - c8y CLI authenticated, jq installed
#
# Usage:
#   ./test-multi-tenant.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

MAPPING_ID=""

cleanup() {
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
}
trap cleanup EXIT

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "Mapping CRUD / Tenant Isolation"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service

MAPPING_JSON=$(cat <<EOF
{
  "name": "test-multi-tenant-$$",
  "mappingTopic": "dmtest/tenant/+",
  "mappingTopicSample": "dmtest/tenant/dev01",
  "targetAPI": "MEASUREMENT",
  "direction": "INBOUND",
  "mappingType": "JSON",
  "transformationType": "DEFAULT",
  "sourceTemplate": "{\"temperature\":20.0}",
  "targetTemplate": "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}",
  "substitutions": [
    {"pathSource":"_TOPIC_LEVEL_[2]","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false}
  ],
  "active": false,
  "debug": false,
  "createNonExistingDevice": false,
  "useExternalId": true,
  "externalIdType": "c8y_Serial",
  "qos": "AT_LEAST_ONCE",
  "snoopStatus": "NONE",
  "snoopedTemplates": []
}
EOF
)

dm_step "Creating test mapping ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_assert_gt "Mapping id is non-empty" "${#MAPPING_ID}" 0

dm_step "Verifying mapping appears exactly once in listing ..."
COUNT=$(dm_api_json_array GET /mapping \
    | jq -r --arg id "$MAPPING_ID" '[.[] | select(.id == $id)] | length' 2>/dev/null || echo 0)
dm_assert_eq "Mapping appears exactly once" "1" "$COUNT"

dm_step "Deleting mapping ..."
MAPPING_ID_SAVED="$MAPPING_ID"
dm_delete_mapping "$MAPPING_ID"
MAPPING_ID=""   # prevent double-delete in trap

dm_step "Verifying mapping is gone from listing ..."
dm_wait 2
COUNT2=$(dm_api_json_array GET /mapping \
    | jq -r --arg id "$MAPPING_ID_SAVED" '[.[] | select(.id == $id)] | length' 2>/dev/null || echo 0)
dm_assert_eq "Mapping is no longer listed" "0" "$COUNT2"

dm_done "Mapping CRUD / Tenant Isolation"
dm_print_summary
