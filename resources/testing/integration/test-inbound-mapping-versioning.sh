#!/bin/bash
#
# test-inbound-mapping-versioning: draft -> publish -> activate -> rollback, end to end
#
# Mapping versioning lets a running mapping be edited without disturbing it. Edits go to a
# draft, a draft is published as an immutable semver version, and a version is activated to
# become the running configuration. This test drives the REST API and proves the pipeline
# really switches behaviour (each version emits a different measurement type). Steps:
#   1. a mapping created through the API gets its 1.0.0 record right away; a pre-versioning
#      (legacy/imported) mapping is simulated by removing that record -> listing shows the running
#      configuration as a virtual first version and does NOT write a record (a read must not mutate)
#   2. PUT /mapping/{id} cannot change version bookkeeping
#   3. activate -> v1 record is created, message yields the v1 measurement type
#   4. save a draft          -> draftDirty, running configuration untouched
#   5. publish rejections    -> duplicate / non-canonical duplicate / unparseable labels
#   6. publish 2.0.0         -> second version, draft consumed, active version unchanged
#   7. the active version cannot be deleted
#   8. activate 2.0.0 (roll forward) -> message yields the v2 measurement type
#   9. activate 1.0.0 (rollback)     -> message yields the v1 measurement type again
#  10. activating an unknown version is rejected, running version untouched
#  11. an inactive version can be deleted
#  12. deleting the mapping removes its version records
#
# Prerequisites: MQTT connector (public broker or MQTT Service), mosquitto_pub, c8y CLI.
#
# Usage: ./test-inbound-mapping-versioning.sh [--keep] [--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="Mapping versioning (draft, publish, activate, rollback)"

EXT_ID="dmtest-versioning-$(date +%s)"
IDENTIFIER="vers$$"
TOPIC="dmtest/versioning/${EXT_ID}"
TYPE_V1="dmtest_ver_v1_$$"
TYPE_V2="dmtest_ver_v2_$$"
MAPPING_ID=""
DEVICE_ID=""

cleanup() {
    dm_info "Cleaning up ..."
    [ -n "$MAPPING_ID" ] && dm_delete_mapping "$MAPPING_ID" 2>/dev/null || true
    [ -z "${DEVICE_ID:-}" ] && DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial") || true
    if [ -n "${DEVICE_ID:-}" ]; then
        c8y identity delete --name "$EXT_ID" --type "c8y_Serial" --force </dev/null 2>/dev/null || true
        dm_delete_device "$DEVICE_ID"
    fi
}

# ── Local helpers ──────────────────────────────────────────────────────────────

# GET /mapping/{id}/version as a plain JSON array (dm_api may print NDJSON or an array).
versions_json() {
    dm_api GET "/mapping/${MAPPING_ID}/version" | jq -sc 'flatten(1)'
}

version_count() {
    versions_json | jq 'length'
}

# Number of persisted d11r_mapping_version records of this line (virtual versions are not records).
version_record_count() {
    c8y api --method GET \
        --url "/inventory/managedObjects?type=d11r_mapping_version&pageSize=2000" \
        --force --raw </dev/null 2>/dev/null \
        | jq --arg i "$IDENTIFIER" '[.managedObjects[]? | select(tostring | contains($i))] | length' \
        2>/dev/null || printf '0'
}

# Delete this line's version records straight from the inventory — turns the mapping into what a
# pre-versioning / imported one looks like (a running line without any version record).
delete_version_records() {
    local _id
    while IFS= read -r _id; do
        [ -n "$_id" ] && c8y inventory delete --id "$_id" --force </dev/null >/dev/null 2>&1 || true
    done < <(c8y api --method GET \
        --url "/inventory/managedObjects?type=d11r_mapping_version&pageSize=2000" \
        --force --raw </dev/null 2>/dev/null \
        | jq -r --arg i "$IDENTIFIER" '.managedObjects[]? | select(tostring | contains($i)) | .id' 2>/dev/null)
}

# Field of the running mapping line.
mapping_field() {   # <jq filter>
    dm_api GET "/mapping/${MAPPING_ID}" | jq -r "$1"
}

# Type of the newest measurement of the test device (empty while there is none).
latest_measurement_type() {
    local _id
    _id=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")
    [ -n "$_id" ] || { printf ''; return 0; }
    c8y api --method GET \
        --url "/measurement/measurements?source=${_id}&pageSize=1&revert=true" \
        --force --raw </dev/null 2>/dev/null \
        | jq -r '.measurements[0].type // empty' 2>/dev/null || printf ''
}

# Publish a message and wait until the newest measurement has the expected type. Because the
# expected type always differs from the previous one, this cannot pass on stale data.
publish_and_expect_type() {   # <label> <expected_type> <temperature>
    local _label=$1 _expected=$2 _temp=$3 _got=""
    dm_mqtt_publish "$TOPIC" "{\"temperature\":${_temp}}" 1
    for _i in $(seq 1 15); do
        _got=$(latest_measurement_type)
        [ "$_got" = "$_expected" ] && break
        sleep 2
    done
    dm_assert_eq "$_label" "$_expected" "$_got"
}

activate_version() {   # <version>
    dm_api_must POST /operation \
        "{\"operation\":\"ACTIVATE_MAPPING\",\"parameter\":{\"id\":\"${MAPPING_ID}\",\"active\":\"true\",\"version\":\"$1\"}}" >/dev/null
    dm_info "Activated version $1"
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step 1 "Validating environment"
dm_test_setup_and_validate
dm_validate_only_exit

dm_step 2 "Creating a mapping and turning it into a pre-versioning one"
MAPPING_JSON=$(jq -cn --arg name "test-versioning-$$" --arg id "$IDENTIFIER" --arg ext "$EXT_ID" --arg type "$TYPE_V1" '{
  name: $name,
  identifier: $id,
  mappingTopic: "dmtest/versioning/+",
  mappingTopicSample: ("dmtest/versioning/" + $ext),
  targetAPI: "MEASUREMENT",
  direction: "INBOUND",
  mappingType: "JSON",
  transformationType: "DEFAULT",
  sourceTemplate: "{\"temperature\":20.0}",
  targetTemplate: ("{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"" + $type + "\"}"),
  substitutions: [
    {"pathSource":"_TOPIC_LEVEL_[2]","pathTarget":"_IDENTITY_.externalId","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"temperature","pathTarget":"c8y_TemperatureMeasurement.T.value","repairStrategy":"DEFAULT","expandArray":false},
    {"pathSource":"$now()","pathTarget":"time","repairStrategy":"DEFAULT","expandArray":false}
  ],
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
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"

BASE_VERSION=$(mapping_field '.version')
dm_info "Initial mapping version: ${BASE_VERSION}"

dm_assert_eq "Creating the mapping through the API wrote its 1.0.0 record" "1" "$(version_record_count)"
delete_version_records
dm_assert_eq "Simulated pre-versioning mapping: no version record" "0" "$(version_record_count)"

dm_info "Listing the versions of a pre-versioning mapping (twice)"
dm_assert_eq "Listing shows one (virtual) version" "1" "$(version_count)"
dm_assert_eq "Virtual version carries the running version label" "$BASE_VERSION" "$(versions_json | jq -r '.[0].version')"
dm_assert_eq "Virtual version has no record id" "null" "$(versions_json | jq -r '.[0].id')"
dm_assert_eq "Reading versions again still shows the virtual version" "null" "$(versions_json | jq -r '.[0].id')"
dm_assert_eq "Reading versions did not write any version record" "0" "$(version_record_count)"

dm_step 3 "PUT /mapping/{id} must not change version bookkeeping"
FORGED=$(dm_api GET "/mapping/${MAPPING_ID}" | jq -c '.version = "9.9.9" | .versionNote = "forged" | .draftDirty = true')
dm_api_must PUT "/mapping/${MAPPING_ID}" "$FORGED" >/dev/null
dm_assert_eq "Client-supplied version ignored" "$BASE_VERSION" "$(mapping_field '.version')"
dm_assert_eq "Client-supplied draftDirty ignored" "false" "$(mapping_field '.draftDirty // false')"

dm_step 4 "Activating creates the first version record; the v1 configuration runs"
dm_activate_mapping "$MAPPING_ID"
dm_assert_mqtt_topics_active "$MAPPING_ID"
publish_and_expect_type "v1 measurement type produced" "$TYPE_V1" 21.0
DEVICE_ID=$(dm_lookup_device_by_ext_id "$EXT_ID" "c8y_Serial")
dm_assert_eq "Activation persisted the first version record" "1" "$(version_record_count)"
dm_assert_eq "One version listed" "1" "$(version_count)"
dm_assert_ne "Listed version is now a real record" "null" "$(versions_json | jq -r '.[0].id')"

IFS=. read -r _MAJ _MIN _PAT <<<"$BASE_VERSION"
dm_assert_eq "Suggested next patch version" "${_MAJ}.${_MIN}.$((_PAT + 1))" \
    "$(dm_api GET "/mapping/${MAPPING_ID}/version/suggest" | jq -r '.patch')"

dm_step 5 "Saving a draft does not touch the running mapping"
DRAFT=$(dm_api GET "/mapping/${MAPPING_ID}" \
    | jq -c --arg v1 "$TYPE_V1" --arg v2 "$TYPE_V2" '.targetTemplate |= gsub($v1; $v2) | .lastUpdate = 0')
dm_api_must PUT "/mapping/${MAPPING_ID}/draft" "$DRAFT" >/dev/null
dm_assert_eq "Line flagged as having unpublished edits" "true" "$(mapping_field '.draftDirty')"
dm_assert_eq "Draft holds the edit" "true" \
    "$(dm_api GET "/mapping/${MAPPING_ID}/draft" | jq -r --arg v2 "$TYPE_V2" '(.targetTemplate // "") | contains($v2)')"
dm_assert_eq "Running mapping still has the v1 configuration" "true" \
    "$(mapping_field '.targetTemplate' | jq -Rr --arg v1 "$TYPE_V1" 'contains($v1)')"

dm_step 6 "Publish rejections leave the history untouched"
dm_assert_rejected "Duplicate label" POST "/mapping/${MAPPING_ID}/publish?version=${BASE_VERSION}"
if [ "$BASE_VERSION" = "1.0.0" ]; then
    dm_assert_rejected "Padded duplicate ' 1.0.0'" POST "/mapping/${MAPPING_ID}/publish?version=%201.0.0"
    dm_assert_rejected "Non-canonical duplicate '01.0.0'" POST "/mapping/${MAPPING_ID}/publish?version=01.0.0"
else
    dm_warn "Base version is ${BASE_VERSION}, not 1.0.0 — skipping the canonical-duplicate checks"
fi
dm_assert_rejected "Component overflowing int" POST "/mapping/${MAPPING_ID}/publish?version=99999999999.0.0"
dm_assert_rejected "Not a semver" POST "/mapping/${MAPPING_ID}/publish?version=latest"
dm_assert_eq "Still one version after the rejected publishes" "1" "$(version_count)"
# The draft is a version record too (isDraft), so: 1 published version + 1 draft.
dm_assert_eq "No record was written by the rejected publishes (1 published + the draft)" "2" "$(version_record_count)"

dm_step 7 "Publishing 2.0.0 consumes the draft; the active version does not change"
dm_api_must POST "/mapping/${MAPPING_ID}/publish?version=2.0.0&note=versioning-test" >/dev/null
dm_assert_eq "Two versions listed" "2" "$(version_count)"
dm_assert_eq "2.0.0 is retrievable" "2.0.0" "$(dm_api GET "/mapping/${MAPPING_ID}/version/2.0.0" | jq -r '.version')"
dm_assert_eq "Publishing did not activate it" "$BASE_VERSION" "$(mapping_field '.version')"
dm_assert_eq "Draft flag cleared by publish" "false" "$(mapping_field '.draftDirty // false')"

dm_step 8 "The active version cannot be deleted"
dm_assert_rejected "Delete active version" DELETE "/mapping/${MAPPING_ID}/version/${BASE_VERSION}"
dm_assert_eq "Both versions remain" "2" "$(version_count)"

dm_step 9 "Roll forward: activate 2.0.0 — the pipeline switches behaviour"
activate_version "2.0.0"
dm_assert_eq "Running version is 2.0.0" "2.0.0" "$(mapping_field '.version')"
publish_and_expect_type "v2 measurement type produced" "$TYPE_V2" 22.0

dm_step 10 "Rollback: activate ${BASE_VERSION} — the old behaviour returns"
activate_version "$BASE_VERSION"
dm_assert_eq "Running version is ${BASE_VERSION} again" "$BASE_VERSION" "$(mapping_field '.version')"
publish_and_expect_type "v1 measurement type produced again" "$TYPE_V1" 23.0

dm_step 11 "Activating an unknown version is rejected and changes nothing"
dm_assert_rejected "Unknown version" POST /operation \
    "{\"operation\":\"ACTIVATE_MAPPING\",\"parameter\":{\"id\":\"${MAPPING_ID}\",\"active\":\"true\",\"version\":\"9.9.9\"}}"
dm_assert_eq "Running version untouched" "$BASE_VERSION" "$(mapping_field '.version')"

dm_step 12 "An inactive version can be deleted"
dm_api_must DELETE "/mapping/${MAPPING_ID}/version/2.0.0" >/dev/null
dm_assert_eq "One version left" "1" "$(version_count)"

dm_step 13 "Deleting the mapping removes its version records"
dm_deactivate_mapping "$MAPPING_ID"
dm_api_must DELETE "/mapping/${MAPPING_ID}" >/dev/null
MAPPING_ID=""
dm_assert_eq "No version records left behind" "0" "$(version_record_count)"

dm_done "$TEST_TITLE"
dm_print_summary
