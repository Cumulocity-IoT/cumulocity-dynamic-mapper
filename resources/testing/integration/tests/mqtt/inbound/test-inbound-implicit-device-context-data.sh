#!/bin/bash
#
# test-inbound-implicit-device-context-data.sh
#
# Regression test for GitHub issue #700: contextData returned by a Smart Function
# (deviceName, deviceType, deviceFragments) must be applied to the managed object
# that is auto-created for an unknown device. The Dry Run showed the fragments, but
# the real device lacked them.
#
# The function returns ONLY a measurement (no MANAGED_OBJECT patch), so the fragments
# can reach the device solely through implicit device creation.
#
# Prerequisites: mapper service running, MQTT connector active, c8y CLI authenticated
# Usage: ./test-inbound-implicit-device-context-data.sh [--keep]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEGRATION_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
source "${INTEGRATION_DIR}/test-harness.sh"

TEST_TITLE="Implicit device: contextData applied to created device (#700)"

EXT_ID="dmtest-ctxdata-$(date +%s)"
DEVICE_NAME="dmtest-ctxdata-name-$$"
DEVICE_TYPE="dmtest_CtxDataType"
MAPPING_ID=""
DEVICE_ID=""

dm_parse_args "$@"

cleanup() {
    dm_info "Cleaning up test resources ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
    if [ -z "${DEVICE_ID:-}" ]; then
        DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    fi
    if [ -n "${DEVICE_ID:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" 2>/dev/null || true
        c8y inventory delete --id "$DEVICE_ID" 2>/dev/null || true
    fi
    dm_info "Cleanup complete"
}

dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step 1 "Validating environment"
dm_test_setup_and_validate
dm_validate_only_exit

dm_step 2 "Creating Smart Function mapping that returns contextData"
SF_CODE=$(cat <<'JSCODE'
function onMessage(msg, context) {
    var payload = msg.getPayload();
    return [{
        cumulocityType: "measurement",
        action: "create",
        payload: {
            type: "c8y_TemperatureMeasurement",
            c8y_TemperatureMeasurement: { T: { value: payload.temp, unit: "C" } },
            time: new Date().toISOString()
        },
        externalSource: [{ type: "c8y_Serial", externalId: payload.externalId }],
        contextData: {
            deviceName: payload.deviceName,
            deviceType: payload.deviceType,
            deviceFragments: {
                c8y_SupportedOperations: ["c8y_Command"],
                c8y_LpwanDevice: {
                    provisioned: true,
                    lpwanDeviceType: "Lora",
                    serviceProvider: "Loriot"
                },
                dmtest_Custom: { flag: "from-contextData" }
            }
        }
    }];
}
JSCODE
)

SF_CODE=$(dm_wrap_onmessage_code "$SF_CODE")
SF_CODE_B64=$(printf '%s' "$SF_CODE" | base64)

MAPPING_JSON=$(jq -cn \
    --arg name       "test-ctxdata-$$" \
    --arg identifier "ctxdata-$$" \
    --arg code       "$SF_CODE_B64" \
    --arg extId      "$EXT_ID" \
    '{
      name: $name,
      identifier: $identifier,
      mappingTopic: "dmtest/ctxdata/+",
      mappingTopicSample: ("dmtest/ctxdata/" + $extId),
      targetAPI: "MEASUREMENT",
      direction: "INBOUND",
      mappingType: "JSON",
      transformationType: "SMART_FUNCTION",
      sourceTemplate: "{}",
      targetTemplate: "{}",
      substitutions: [],
      code: $code,
      active: false,
      debug: false,
      createNonExistingDevice: true,
      useExternalId: true,
      externalIdType: "c8y_Serial",
      genericDeviceIdentifier: "_IDENTITY_.externalId",
      qos: "AT_LEAST_ONCE"
    }')

dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_success "Mapping created: $MAPPING_ID"

dm_step 3 "Deploying and activating mapping"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active "$MAPPING_ID"

dm_step 4 "Verifying device does not exist yet"
PRE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
dm_assert_eq "Device does not exist before first message" "" "${PRE_ID:-}"

dm_step 5 "Publishing the first message (triggers device auto-creation)"
PAYLOAD=$(jq -cn --arg extId "$EXT_ID" --arg name "$DEVICE_NAME" --arg type "$DEVICE_TYPE" \
    '{externalId: $extId, deviceName: $name, deviceType: $type, temp: 21.5}')
dm_mqtt_publish "dmtest/ctxdata/$EXT_ID" "$PAYLOAD" 1

dm_step 6 "Asserting the device was created with contextData applied"
if dm_wait_for_device_by_ext_id "$EXT_ID" "c8y_Serial" 30 2; then
    DEVICE_ID="$_DM_LAST_DEVICE_ID"
fi
dm_assert_gt "Device auto-created (id=${DEVICE_ID:-none})" "${#DEVICE_ID}" 0

if [ -n "$DEVICE_ID" ]; then
    dm_assert_mo_field_eventually "deviceName applied"      "$DEVICE_ID" '.name' "$DEVICE_NAME"
    dm_assert_mo_field_eventually "deviceType applied"      "$DEVICE_ID" '.type' "$DEVICE_TYPE"
    dm_assert_mo_field_eventually "c8y_SupportedOperations" "$DEVICE_ID" '.c8y_SupportedOperations[0]' "c8y_Command"
    dm_assert_mo_field_eventually "c8y_LpwanDevice fragment" "$DEVICE_ID" '.c8y_LpwanDevice.serviceProvider' "Loriot"
    dm_assert_mo_field_eventually "custom fragment"         "$DEVICE_ID" '.dmtest_Custom.flag' "from-contextData"
fi

dm_assert_measurement_present "Measurement stored for the new device" "$EXT_ID" "c8y_Serial" 1 15

dm_done "$TEST_TITLE"
dm_print_summary
