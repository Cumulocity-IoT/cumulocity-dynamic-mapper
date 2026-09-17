#!/usr/bin/env bash
#
# setup-test-devices.sh — Test data provisioning for the backfill-subscription
# feature (group-based and type-based dynamic subscriptions).
#
# Usage:
#   ./setup-test-devices.sh [SCENARIO] [count]
#     SCENARIO  a/all, g/group, t/type, c/cleanup, menu number, or omit for
#               the interactive menu.
#     count     number of devices to create (default 100, ignored by cleanup)
#
# Scenarios:
#   1  group    Create "Test-Group" and <count> devices, assigned as childAssets
#               (Test-Device-XXX). Use to exercise group-based dynamic subscription.
#   2  type     Create <count> devices of type "test-type" (Test-Type-Device-XXX),
#               NOT assigned to any group. Use to exercise the type-subscription
#               resync/backfill for devices that already existed before the type
#               was added to the subscription filter.
#   3  cleanup  Delete all devices/group created by the scenarios above.
#
# Examples:
#   ./setup-test-devices.sh                # interactive menu
#   ./setup-test-devices.sh group 50       # 50 devices in a group
#   ./setup-test-devices.sh type           # 100 "test-type" devices
#   ./setup-test-devices.sh all 20         # both scenarios, 20 devices each
#   ./setup-test-devices.sh cleanup        # remove all test devices/group

set -euo pipefail

DEVICE_TYPE="test-type"

# ── ANSI colours ───────────────────────────────────────────────────────────────
if [ -t 1 ]; then
    C_GREEN=$'\033[0;32m'
    C_RED=$'\033[0;31m'
    C_BOLD=$'\033[1m'
    C_DIM=$'\033[2m'
    C_RESET=$'\033[0m'
else
    C_GREEN="" C_RED="" C_BOLD="" C_DIM="" C_RESET=""
fi

_print_header() {
    printf "\n${C_BOLD}%s${C_RESET}\n" "Dynamic Mapper — Backfill Subscription Test Data Setup"
    echo ""
}

_print_menu() {
    _print_header
    printf "  ${C_BOLD}%-4s %s${C_RESET}\n" "#" "Scenario"
    printf "  %s\n" "$(printf '─%.0s' {1..60})"
    printf "  ${C_BOLD}%2d${C_RESET}  %s\n" 1 "Group subscription: 'Test-Group' + N devices (childAssets)"
    printf "  ${C_BOLD}%2d${C_RESET}  %s\n" 2 "Type subscription:  N devices of type '${DEVICE_TYPE}' (no group)"
    printf "  ${C_BOLD}%2d${C_RESET}  %s\n" 3 "Cleanup: delete all test devices and 'Test-Group'"
    echo ""
    printf "  ${C_BOLD}%2s${C_RESET}  %s\n" "a" "Run both create scenarios (1+2)"
    printf "  ${C_BOLD}%2s${C_RESET}  %s\n" "q" "Quit"
    echo ""
}

_confirm() {   # <prompt>
    local reply
    read -r -p "$1 [y/N] " reply
    case "$reply" in
        [yY]|[yY][eE][sS]) return 0 ;;
        *) return 1 ;;
    esac
}

# ── Scenario 1: group of devices (childAssets) ────────────────────────────────
_run_group_scenario() {   # <count>
    local count="$1"
    local group_name="Test-Group"

    printf "\n${C_BOLD}══ Scenario: group subscription (%d devices) ══${C_RESET}\n" "$count"

    echo "Creating group '${group_name}'..."
    local group_id
    group_id=$(c8y devicegroups create --name "${group_name}" --force --output json | jq -r '.id')
    echo "Group created: id=${group_id}"

    _confirm "Group created (id=${group_id}). Continue creating ${count} devices?" || {
        echo "Aborted."
        return 1
    }

    echo "Creating ${count} devices and assigning them to '${group_name}'..."
    local i device_name device_id
    for i in $(seq -w 1 "${count}"); do
        device_name="Test-Device-${i}"
        device_id=$(c8y devices create --name "${device_name}" --force --output json | jq -r '.id')
        c8y devicegroups children assign --id "${group_id}" --child "${device_id}" --childType asset --force >/dev/null
        echo "[${i}/${count}] created ${device_name} (id=${device_id}) and assigned to group ${group_id}"
    done

    printf "%sDone.%s Group '%s' (id=%s) now has %s devices.\n" \
        "${C_GREEN}" "${C_RESET}" "${group_name}" "${group_id}" "${count}"
}

# ── Scenario 2: devices of a type, no group ───────────────────────────────────
_run_type_scenario() {   # <count>
    local count="$1"

    printf "\n${C_BOLD}══ Scenario: type subscription (%d devices of type '%s') ══${C_RESET}\n" \
        "$count" "$DEVICE_TYPE"

    _confirm "Create ${count} devices of type '${DEVICE_TYPE}'?" || {
        echo "Aborted."
        return 1
    }

    echo "Creating ${count} devices of type '${DEVICE_TYPE}'..."
    local i device_name device_id
    for i in $(seq -w 1 "${count}"); do
        device_name="Test-Type-Device-${i}"
        device_id=$(c8y devices create --name "${device_name}" --type "${DEVICE_TYPE}" --force --output json | jq -r '.id')
        echo "[${i}/${count}] created ${device_name} (id=${device_id}, type=${DEVICE_TYPE})"
    done

    printf "%sDone.%s Created %s devices of type '%s'.\n" \
        "${C_GREEN}" "${C_RESET}" "${count}" "${DEVICE_TYPE}"
    printf "%sThese devices existed BEFORE any type subscription — use them to verify\n" "${C_DIM}"
    printf "POST /subscription/type/resync/%s backfills them correctly.%s\n" "${DEVICE_TYPE}" "${C_RESET}"
}

# ── Scenario 3: cleanup ────────────────────────────────────────────────────────
_run_cleanup() {
    printf "\n%s══ Cleanup: removing test devices and group ══%s\n" "${C_BOLD}" "${C_RESET}"

    _confirm "Delete all 'Test-Device-*', 'Test-Type-Device-*' devices and 'Test-Group'?" || {
        echo "Aborted."
        return 1
    }

    echo "Deleting devices matching 'Test-Device-*'..."
    c8y devices list --name "Test-Device-*" --includeAll \
        | c8y devices delete --force --allowEmptyPipe

    echo "Deleting devices matching 'Test-Type-Device-*'..."
    c8y devices list --name "Test-Type-Device-*" --includeAll \
        | c8y devices delete --force --allowEmptyPipe

    echo "Deleting group 'Test-Group'..."
    c8y devicegroups list --name "Test-Group" --includeAll \
        | c8y devicegroups delete --force --allowEmptyPipe

    printf "%sDone.%s Cleanup complete.\n" "${C_GREEN}" "${C_RESET}"
}

_run_scenario() {   # <scenario> <count>
    case "$1" in
        1|g|group) _run_group_scenario "$2" ;;
        2|t|type)  _run_type_scenario "$2" ;;
        3|c|cleanup) _run_cleanup ;;
        a|all)
            _run_group_scenario "$2"
            _run_type_scenario "$2"
            ;;
        *)
            printf "${C_RED}ERROR: unknown scenario '%s'${C_RESET}\n" "$1" >&2
            exit 1
            ;;
    esac
}

# ── Entry point ────────────────────────────────────────────────────────────────
SCENARIO="${1:-}"
COUNT="${2:-100}"

if [ -z "$SCENARIO" ]; then
    _print_menu
    printf "Select scenario (1, 2, 3, a): "
    read -r SCENARIO
    [ "$SCENARIO" = "q" ] && exit 0
    case "$SCENARIO" in
        3|c|cleanup) ;;
        *)
            printf "Number of devices [default 100]: "
            read -r _count_reply
            COUNT="${_count_reply:-100}"
            ;;
    esac
fi

_run_scenario "$SCENARIO" "$COUNT"
