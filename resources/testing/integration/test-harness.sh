#!/bin/bash
#
# test-harness.sh — Common functions for Dynamic Mapper integration tests
#
# Source this file at the top of each test script:
#   source "$(dirname "$0")/test-harness.sh"
#
# Functions provided
# ──────────────────
# Output
#   dm_banner  <title>
#   dm_step    <description>           — no step number
#   dm_step    <n> <description>       — with step number prefix
#   dm_done    <title>
#   dm_info    <msg>
#   dm_success <msg>
#   dm_warn    <msg>
#   dm_fail    <msg>
#
# Validation
#   dm_validate_tools                   — verify all required tools are installed
#   dm_test_setup_and_validate          — complete setup validation (session, tools, service, mqtt)
#   dm_verify_mqtt_connector_ready      — check MQTT connector is CONNECTED
#   dm_assert_mqtt_topics_active [mapping_id] — the mapping's own topic is subscribed
#   dm_get_support_esm                  — fetch supportESM from service configuration
#   dm_wrap_onmessage_code              — append export { onMessage } only when supportESM=true
#
# CLI flags / lifecycle
#   dm_parse_args        "$@"             — parse --cleanup / --keep / --validate-only
#   dm_register_cleanup  <function>       — run <function> on exit unless --keep
#   dm_validate_only_exit                 — exit 0 here when --validate-only was passed
#
# Assertions  (update _DM_PASS_COUNT / _DM_FAIL_COUNT)
#   dm_assert_eq      <label> <expected> <actual>
#   dm_assert_gt      <label> <value>    <than>
#   dm_assert_num_eq  <label> <expected_num> <actual_num> [scale=1]
#   dm_assert_eq_zero <label> <value>
#   dm_wait_for_device_by_ext_id <ext_id> <ext_id_type> [timeout] [interval]
#   dm_assert_measurement_present <label> <ext_id> <ext_id_type> [min] [timeout]
#   dm_assert_event_present       <label> <ext_id> <ext_id_type> [min] [timeout]
#   dm_assert_alarm_present       <label> <ext_id> <ext_id_type> [min] [timeout]
#   dm_assert_operation_present   <label> <ext_id> <ext_id_type> [min] [timeout]
#   dm_wait_for_mapping_processing <mapping_id> <baseline_count> [timeout] [interval]
#   dm_print_summary  — prints pass/fail totals; exits 1 when any failure
#
# Devices
#   dm_create_device <name> <type>
#     → sets _DM_LAST_DEVICE_ID and _DM_LAST_DEVICE_NAME
#   dm_delete_device <id>
#   dm_send_measurement <device_id> <temp_value> [unit=C]
#
# Dynamic Mapper API
#   dm_api              <method> <path> [json_body]
#   dm_api_json_array   <method> <path> [json_body]   (returns [] on error)
#
# Subscriptions
#   dm_count_subscriptions   <device_id>
#     → sets _DM_LAST_SUB_COUNT; prints raw subscription JSON
#   dm_subscription_names_json <subscriptions_json>
#   dm_assert_has_subscription <label> <device_id>
#   dm_assert_no_subscription  <label> <device_id>
#   dm_show_subscriptions      <device_id>
#   dm_delete_static_subscription <device_id> <subscription_name>
#   dm_create_static_subscription_resolve_name <api> <device_id> <device_name> [wait]
#     → sets _DM_LAST_SUBSCRIPTION_NAME and prints it
#   dm_set_type_subscriptions     <api> <types_json_array>   (verified read-after-write)
#     e.g. dm_set_type_subscriptions MEASUREMENT '["auto-type"]'
#          dm_set_type_subscriptions MEASUREMENT '[]'   # clear
#     _DM_TYPE_SUB_VERIFY=false downgrades the verification to a warning (cleanup paths)
#   dm_wait_for_type_subscription      <type> [timeout] [interval]
#   dm_assert_type_subscription_present <label> <type> [timeout]
#
# Waiting
#   dm_wait             <seconds> <reason>
#   dm_wait_for_service [max_retries=24] [interval_secs=10]
#
# Connectors
#   dm_require_mqtt_broker          — validate broker reachability; set _DM_MQTT_CONNECTOR_ID
#   dm_setup_mqtt_test_connector    — create a new MQTT connector config (disabled)
#   dm_enable_connector             — enable a connector (required before connect)
#   dm_setup_and_connect_mqtt_connector — complete setup: create, enable, and connect
#   dm_connect_connector            <connectorIdentifier>
#   dm_disconnect_connector         <connectorIdentifier>
#   dm_get_connector_status         <connectorIdentifier>
#   dm_assert_connector_status      <label> <connectorIdentifier> <expected_status>
#
# MQTT Publish/Subscribe
#   dm_mqtt_publish                 <topic> <payload> [qos=1]
#   dm_mqtt_subscribe_one           <topic> [timeout_secs=10]
#   dm_mqtt_probe_subscription      <topic> [timeout_secs=10]
#
# Environment overrides (set before sourcing or exporting)
#   DM_SERVICE                  (default /service/dynamic-mapper-service)
#   DM_DEFAULT_DISCOVERY_WAIT   (default 10)
#   DM_DEFAULT_STARTUP_WAIT     (default 60)
#   DM_DEFAULT_HEALTH_RETRIES   (default 24)
#   DM_DEFAULT_HEALTH_INTERVAL  (default 10)


# ── Layout ────────────────────────────────────────────────────────────────────
# This file is a thin entry point. The helpers live in lib/ (one file per concern):
#   core.sh           config, colours, output, tool validation, assertions, CLI flags, waits
#   api.sh            devices, Dynamic Mapper REST API, Cumulocity data / inventory
#   subscriptions.sh  static / type / group subscriptions
#   mappings.sh       Smart Function helpers, mapping lifecycle and deployment
#   connectors.sh     connector lifecycle, http-polling-mock (REST_POLLING / WEB_HOOK)
#   mqtt.sh           broker selection (g / m), MQTT Service certificates, publish/subscribe
# Tests keep sourcing this file; the old path is also used by resources/testing/performance.

# ── Guard against double-sourcing ─────────────────────────────────────────────
[ -n "${_DM_HARNESS_LOADED:-}" ] && return 0
_DM_HARNESS_LOADED=1

# The folder holding this file (and run-tests.sh) — set before the libs are sourced.
_DM_INTEGRATION_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

for _dm_lib in core api subscriptions mappings connectors mqtt; do
    # shellcheck source=/dev/null
    source "${_DM_INTEGRATION_DIR}/lib/${_dm_lib}.sh"
done
unset _dm_lib
