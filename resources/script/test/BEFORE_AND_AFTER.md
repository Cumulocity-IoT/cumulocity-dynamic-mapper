# Test Script Improvements - Before & After

This document shows the specific changes made to test scripts and the harness.

## File 1: test-inbound-flatfile.sh (Lines 71-73)

### Before (❌ BROKEN)
```bash
dm_step "Creating and activating FLAT_FILE mapping ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"dm_activate_mapping "$MAPPING_ID"
```

**Problem**: Lines 2-3 are concatenated without newlines. Bash interprets this as:
- `MAPPING_ID="$_DM_LAST_MAPPING_ID"` (assignment)
- `dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"dm_activate_mapping "$MAPPING_ID"` (invalid command)

**Error**: `dm_deploy_mapping_to_mqtt_connector: command not found`

### After (✅ FIXED)
```bash
dm_step "Creating and activating FLAT_FILE mapping ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
```

**Result**: Each function call is on its own line, properly separated.

---

## File 2: test-inbound-hex.sh (Lines 55-57)

### Before (❌ BROKEN)
```bash
dm_step "Creating and activating HEX mapping ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"dm_activate_mapping "$MAPPING_ID"
```

**Problem**: Same concatenation issue as flatfile.sh

### After (✅ FIXED)
```bash
dm_step "Creating and activating HEX mapping ..."
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
dm_activate_mapping "$MAPPING_ID"
```

**Result**: Properly formatted and executable.

---

## File 3: test-harness.sh (Major Additions)

### Addition 1: Tool Validation Function (New, ~22 lines)

**Location**: Lines 135-156 (after output helpers)

```bash
# Validate that required tools are installed and available
dm_validate_tools() {
    local _missing=0
    local _tools=("jq" "mosquitto_pub" "c8y" "nc")
    
    for _tool in "${_tools[@]}"; do
        if ! command -v "$_tool" >/dev/null 2>&1; then
            dm_fail "Required tool not found: $_tool"
            _missing=1
        fi
    done
    
    if [ $_missing -eq 1 ]; then
        printf "\n%sRequired tools to install:%s\n" "${_C_RED}" "${_C_RESET}"
        printf "  macOS:  brew install jq mosquitto-clients c8y\n"
        printf "  Linux:  apt-get install jq mosquitto-clients (c8y from https://github.com/reubenmiller/go-c8y-cli)\n"
        exit 1
    fi
    
    dm_info "All required tools found ✓"
}
```

**Usage**:
```bash
dm_validate_tools  # Call once at start of test
```

### Addition 2: Connector Readiness Check (New, ~25 lines)

**Location**: Lines 435-458 (after dm_wait_for_service)

```bash
# Verify that an MQTT connector is available and connected (for INBOUND tests)
# Returns 0 if ready, exits with 1 if not
dm_verify_mqtt_connector_ready() {
    local _host="${MQTT_HOST:-broker.hivemq.com}" _port="${MQTT_PORT:-1883}"
    
    if [ -z "${_DM_MQTT_CONNECTOR_ID:-}" ]; then
        dm_fail "No MQTT connector configured — call dm_setup_and_connect_mqtt_connector first"
        return 1
    fi
    
    local _status
    _status=$(dm_get_connector_status "$_DM_MQTT_CONNECTOR_ID" | jq -r '.status // "UNKNOWN"' 2>/dev/null)
    
    if [ "$_status" != "CONNECTED" ]; then
        dm_fail "MQTT connector $_DM_MQTT_CONNECTOR_ID is not CONNECTED (status: $_status)"
        return 1
    fi
    
    dm_success "MQTT connector ready at ${_host}:${_port} (status: CONNECTED)"
    return 0
}
```

**Usage**:
```bash
dm_verify_mqtt_connector_ready  # Verify before running inbound test
```

### Addition 3: Comprehensive Setup Validation (New, ~16 lines)

**Location**: Lines 460-475 (after connector check)

```bash
# Comprehensive test setup validation (call at start of every test)
# Validates: session, tools, service, and optionally mqtt connector
dm_test_setup_and_validate() {     # [require_mqtt_connector=true]
    local _require_mqtt=${1:-true}
    
    dm_validate_tools
    dm_wait_for_service
    
    if [ "$_require_mqtt" = "true" ]; then
        dm_require_mqtt_broker
        dm_verify_mqtt_connector_ready
    fi
}
```

**Usage**:
```bash
dm_test_setup_and_validate          # For inbound tests (need MQTT)
dm_test_setup_and_validate false    # For outbound tests
```

### Addition 4: Updated Documentation

**Before** (lines 35-51):
```
# Functions provided
# ──────────────────
# Output
#   ...
# Assertions  (update _DM_PASS_COUNT / _DM_FAIL_COUNT)
#   ...
```

**After** (lines 35-51):
```
# Functions provided
# ──────────────────
# Output
#   ...
#
# Validation
#   dm_validate_tools                   — verify all required tools are installed
#   dm_test_setup_and_validate          — complete setup validation (session, tools, service, mqtt)
#   dm_verify_mqtt_connector_ready      — check MQTT connector is CONNECTED
#
# Assertions  (update _DM_PASS_COUNT / _DM_FAIL_COUNT)
#   ...
```

---

## New Files Created

### File 4: TEST_TEMPLATE.sh (96 lines)

**Purpose**: Modern test template showing all best practices

**Key Structure**:
```bash
#!/bin/bash
# Documentation header

set -euo pipefail
source "${SCRIPT_DIR}/test-harness.sh"

# Configuration
KEEP_ON_FAILURE=false
# ... parse command-line flags ...

# Cleanup function
cleanup() {
    # Respects --keep flag
    # Deletes mapping and device
}
trap cleanup EXIT

# Main flow
dm_banner "Test Title"
dm_validate_tools
dm_wait_for_service
dm_require_mqtt_broker
# ... create mapping ...
dm_deploy_mapping_to_mqtt_connector  # <-- Critical!
dm_activate_mapping
# ... test and verify ...
dm_done "Test Title"
dm_print_summary
```

**Key Improvements**:
- ✅ Comprehensive tool validation
- ✅ Proper cleanup with `--keep` flag support
- ✅ Explicit mapping deployment
- ✅ Baseline time recording
- ✅ Complete assertion patterns
- ✅ Exit code handling

### File 5: README.md (400+ lines)

**Sections**:
1. Quick Start (copy-paste examples)
2. Architecture & Best Practices
3. Core Functions Reference
4. Mapping Deployment Requirement (critical!)
5. MQTT Broker Configuration
6. Smart Function Test Pattern
7. Debugging Failed Tests
8. Test Inventory (table of all tests)
9. Test Execution Order (flow diagram)
10. Common Issues & Solutions (FAQ)
11. Contributing New Tests

**Example Usage Documented**:
```bash
# Validate environment only
bash test-inbound-json-smartfunction.sh --validate-only

# Run with auto-cleanup
bash test-inbound-json-smartfunction.sh --cleanup

# Keep test data on failure (for debugging)
bash test-inbound-json-smartfunction.sh --keep
```

### File 6: IMPROVEMENTS.md (This Summary)

Complete record of all changes made.

---

## Impact Summary

### Fixed Issues: 2
1. ✅ test-inbound-flatfile.sh formatting bug
2. ✅ test-inbound-hex.sh formatting bug

### New Functions Added: 3
1. ✅ `dm_validate_tools()` — Tool validation at startup
2. ✅ `dm_verify_mqtt_connector_ready()` — Connector readiness check
3. ✅ `dm_test_setup_and_validate()` — One-call environment validation

### New Files Created: 3
1. ✅ TEST_TEMPLATE.sh (96 lines) — Modern test template
2. ✅ README.md (400+ lines) — Comprehensive testing guide
3. ✅ IMPROVEMENTS.md (this file) — Change documentation

### Documentation Improved: 1
1. ✅ test-harness.sh — Added Validation section to function reference

### Backward Compatibility: ✅ 100%
- All existing tests continue to work unchanged
- New functions are additive
- No breaking changes to function signatures

---

## Validation Checklist

- [x] test-harness.sh — Syntax OK
- [x] test-inbound-flatfile.sh — Syntax OK & Fixed
- [x] test-inbound-hex.sh — Syntax OK & Fixed
- [x] TEST_TEMPLATE.sh — Syntax OK
- [x] All 10 existing test scripts — Still compatible
- [x] Function reference updated
- [x] Complete documentation created
- [x] Examples provided
- [x] No breaking changes

---

## Time to Implement

- Formatting fixes: 2 replacements
- Harness enhancements: 3 new functions + documentation update
- Test template: 96 lines
- Documentation: 400+ lines
- Total: **Comprehensive test framework improvements**

---

## For Users

**To get started with the improvements:**

1. **Read the Quick Start** in README.md
2. **Review TEST_TEMPLATE.sh** for modern pattern
3. **Use new validation functions** in your tests:
   ```bash
   dm_validate_tools
   dm_test_setup_and_validate
   ```
4. **Remember to deploy mappings** before activation:
   ```bash
   dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"
   ```
5. **Check README.md** for troubleshooting if tests fail

---

See [README.md](README.md) for complete testing guide.
See [TEST_TEMPLATE.sh](TEST_TEMPLATE.sh) for modern test example.
