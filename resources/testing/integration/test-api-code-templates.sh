#!/bin/bash
#
# test-api-code-templates: /configuration/code CRUD
#
#   - GET list contains the built-in templates (SYSTEM, SHARED, ...)
#   - POST creates a user template; duplicate POST -> 409
#   - GET by id returns it; code round-trips (base64)
#   - PUT updates it; PUT with mismatching body id -> 400
#   - DELETE removes it; GET afterwards and DELETE again -> 404
#   - DELETE of an internal template and of SHARED is refused (406)
#   - requires ROLE_DYNAMIC_MAPPER_ADMIN for writes (not asserted: the test user is an admin)
#
# Usage:
#   ./test-api-code-templates.sh [--cleanup|--keep|--validate-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

TEST_TITLE="REST: /configuration/code CRUD"

TPL_ID="dmtest-tpl-$(date +%s)"
CREATED=false

cleanup() {
    dm_info "Cleaning up ..."
    [ "$CREATED" = true ] && dm_api DELETE "/configuration/code/${TPL_ID}" >/dev/null || true
}

dm_parse_args "$@"
dm_register_cleanup cleanup

dm_banner "$TEST_TITLE"

dm_step "Waiting for Dynamic Mapper service ..."
dm_test_setup_and_validate false
dm_validate_only_exit

mk_tpl() {   # <name> <code-text>
    jq -cn --arg id "$TPL_ID" --arg name "$1" --arg code "$(printf '%s' "$2" | base64 | tr -d '\n')" '{
      id:$id, name:$name, description:"integration test template", templateType:"INBOUND_SMART_FUNCTION",
      code:$code, internal:false, readonly:false, defaultTemplate:false}'
}

dm_step "List contains the built-in templates ..."
LIST=$(dm_api GET /configuration/code)
dm_assert_gt "Built-in templates present" "$(printf '%s' "$LIST" | jq -r 'keys | length')" 0
dm_assert_eq "SHARED template present" "true" "$(printf '%s' "$LIST" | jq -r 'has("SHARED")')"

dm_step "Create ..."
dm_api_must POST /configuration/code "$(mk_tpl "dmtest template" "// v1")" >/dev/null
CREATED=true
dm_assert_rejected "Duplicate create" POST /configuration/code "$(mk_tpl "dmtest template" "// v1")"

dm_step "Read back ..."
GOT=$(dm_api GET "/configuration/code/${TPL_ID}")
dm_assert_eq "id" "$TPL_ID" "$(printf '%s' "$GOT" | jq -r '.id')"
dm_assert_eq "templateType" "INBOUND_SMART_FUNCTION" "$(printf '%s' "$GOT" | jq -r '.templateType')"
dm_assert_eq "direction derived from templateType" "INBOUND" "$(printf '%s' "$GOT" | jq -r '.direction')"
dm_assert_eq "code contains the stored text" "true" \
    "$(printf '%s' "$GOT" | jq -r '.code' | base64 -d 2>/dev/null | grep -q 'v1' && echo true || echo false)"
dm_assert_eq "appears in list" "true" "$(dm_api GET /configuration/code | jq -r --arg id "$TPL_ID" 'has($id)')"

dm_step "Update ..."
dm_api_must PUT "/configuration/code/${TPL_ID}" "$(mk_tpl "dmtest template renamed" "// v2")" >/dev/null
GOT=$(dm_api GET "/configuration/code/${TPL_ID}")
dm_assert_eq "name updated" "dmtest template renamed" "$(printf '%s' "$GOT" | jq -r '.name')"
dm_assert_eq "code updated" "true" \
    "$(printf '%s' "$GOT" | jq -r '.code' | base64 -d 2>/dev/null | grep -q 'v2' && echo true || echo false)"
dm_assert_rejected "PUT with mismatching body id" PUT "/configuration/code/${TPL_ID}" \
    "$(mk_tpl x '// x' | jq -c '.id="other-id"')"

dm_step "Protected templates cannot be deleted ..."
dm_assert_rejected "Delete SHARED" DELETE /configuration/code/SHARED
INTERNAL=$(dm_api GET /configuration/code | jq -r 'to_entries | map(select(.value.internal == true and .key != "SHARED")) | .[0].key // empty')
if [ -n "$INTERNAL" ]; then
    dm_assert_rejected "Delete internal template $INTERNAL" DELETE "/configuration/code/${INTERNAL}"
else
    dm_warn "No internal template besides SHARED found; skipping that assertion"
fi

dm_step "Delete ..."
dm_api_must DELETE "/configuration/code/${TPL_ID}" >/dev/null
CREATED=false
dm_assert_rejected "GET after delete" GET "/configuration/code/${TPL_ID}"
dm_assert_rejected "Second delete" DELETE "/configuration/code/${TPL_ID}"

dm_done "$TEST_TITLE"
dm_print_summary
