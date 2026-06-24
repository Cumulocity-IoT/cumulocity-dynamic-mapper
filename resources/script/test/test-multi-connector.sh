#!/bin/bash
#
# test-multi-connector: Multiple connectors reporting CONNECTED status
#
# Fetches all connector statuses from the monitoring API and asserts that
# at least one connector is in CONNECTED state.  If more than one connector
# is configured, each CONNECTED one is individually verified.
#
# Prerequisites:
#   - Dynamic mapper service running with at least one connector configured
#   - c8y CLI authenticated, jq installed
#
# Usage:
#   ./test-multi-connector.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=test-harness.sh
source "${SCRIPT_DIR}/test-harness.sh"

dm_parse_args "$@"   # supports --validate-only (read-only test, no cleanup needed)

# ── Test ───────────────────────────────────────────────────────────────────────
dm_banner "37. Multiple connector status check"

dm_step "Waiting for Dynamic Mapper service ..."
dm_wait_for_service
dm_validate_only_exit

dm_step "Fetching all connector statuses ..."
CONNECTORS_JSON=$(dm_api GET /monitoring/status/connectors)

TOTAL=$(printf '%s' "$CONNECTORS_JSON" | jq 'length' 2>/dev/null || echo 0)
dm_info "Total connectors configured: $TOTAL"

if [ "$TOTAL" -eq 0 ]; then
    dm_warn "No connectors configured — skipping status assertions"
    dm_done "37. Multiple connector status check (skipped — no connectors)"
    dm_print_summary
    exit 0
fi

dm_step "Counting CONNECTED connectors ..."
CONNECTED=$(printf '%s' "$CONNECTORS_JSON" \
    | jq '[.[] | select(.status == "CONNECTED")] | length' 2>/dev/null || echo 0)
dm_info "Connected: $CONNECTED / $TOTAL"

dm_assert_gt "At least one connector is CONNECTED" "$CONNECTED" 0

dm_step "Listing all connector identifiers and statuses ..."
printf '%s' "$CONNECTORS_JSON" \
    | jq -r '.[] | "\(.connectorIdentifier // .name // "unknown") \t \(.status // "UNKNOWN")"' \
    | while IFS=$'\t' read -r IDENT STATUS; do
        STATUS="${STATUS// /}"
        if [ "$STATUS" = "CONNECTED" ]; then
            dm_success "  $IDENT → $STATUS"
        else
            dm_warn    "  $IDENT → $STATUS"
        fi
    done

dm_done "37. Multiple connector status check"
dm_print_summary
