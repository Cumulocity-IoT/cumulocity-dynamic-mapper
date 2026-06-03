# Dynamic Mapper Integration Tests

This directory contains bash-based integration tests for the Dynamic Mapper's inbound and outbound transformation pipelines. All tests use the public HiveMQ MQTT broker by default.

## Quick Start

```bash
# Validate environment (no test data modified)
bash test-inbound-json-smartfunction.sh --validate-only

# Run a single test with auto-cleanup
bash test-inbound-json-smartfunction.sh --cleanup

# Run all inbound tests
bash run-tests.sh inbound

# Run all tests
bash run-tests.sh

# Keep test data on failure (for debugging)
bash test-inbound-json-smartfunction.sh --keep
```

## Architecture & Best Practices

### Test Structure

Every test script follows this pattern:

1. **Load harness** — `source test-harness.sh` to get common functions
2. **Parse flags** — Handle `--cleanup`, `--keep`, `--validate-only` options
3. **Validate environment** — Call `dm_validate_tools`, then `dm_wait_for_service`
4. **Setup test data** — Create mapping, deploy to connector, activate
5. **Record baseline** — Use `dm_now -10` to capture test start time
6. **Execute test** — Publish MQTT message or trigger outbound notification
7. **Verify results** — Assert device/measurement/event created with correct values
8. **Cleanup** — DELETE mapping and device (unless `--keep` flag)

### Complete Test Template

See [TEST_TEMPLATE.sh](TEST_TEMPLATE.sh) for a fully documented example with all best practices.

### Core Functions from test-harness.sh

#### Validation & Setup

```bash
# Verify all required tools are installed (mosquitto_pub, c8y, jq, nc)
dm_validate_tools

# Complete environment check: session + tools + service + mqtt connector
dm_test_setup_and_validate [require_mqtt_connector=true]

# Wait for microservice to come UP (liveness probe via GET /mapping)
dm_wait_for_service [max_retries] [interval_secs]

# Verify MQTT connector is configured and CONNECTED
dm_verify_mqtt_connector_ready

# Find or create active MQTT connector (stores ID in _DM_MQTT_CONNECTOR_ID)
dm_require_mqtt_broker
```

#### Connector Management

```bash
# Create disabled MQTT connector (stores ID in _DM_MQTT_CONNECTOR_ID)
dm_setup_mqtt_test_connector [identifier] [name] [mqtt_host] [mqtt_port]

# Enable connector (PUT /configuration/connector/instance/{id})
dm_enable_connector <connectorIdentifier>

# Create, enable, and connect MQTT connector (one-step setup)
dm_setup_and_connect_mqtt_connector [identifier] [name] [mqtt_host] [mqtt_port]
```

#### Mapping Lifecycle

```bash
# Create mapping via POST /mapping (stores ID in _DM_LAST_MAPPING_ID)
dm_create_mapping <json_body>

# Activate mapping (PUT /mapping/{id} with active=true)
dm_activate_mapping <mapping_id>

# Deactivate mapping
dm_deactivate_mapping <mapping_id>

# Deploy mapping to connector (PUT /deployment/defined/{id})
# IMPORTANT: Must call BEFORE dm_activate_mapping for inbound tests
dm_deploy_mapping_to_mqtt_connector <mapping_id>

# Delete mapping (DELETE /mapping/{id})
dm_delete_mapping <mapping_id>
```

#### Device/Measurement Lookup

```bash
# Resolve device ID by external ID and type
# Returns device ID or empty string if not found
DEVICE_ID=$(dm_lookup_device_by_ext_id <externalId> <type>)

# Count measurements for a device since a given timestamp
COUNT=$(dm_count_measurements_since <device_id> <since_iso8601>)

# Assert measurement count is > minimum (updates _DM_PASS_COUNT or _DM_FAIL_COUNT)
dm_assert_measurement_count_gt <label> <device_id> <since_iso8601> <min_count>
```

#### Utilities

```bash
# Generate ISO-8601 UTC timestamp (optionally offset by seconds)
dm_now              # Current time: 2024-01-15T14:23:45.123Z
dm_now -10          # 10 seconds ago

# Wait N seconds with message
dm_wait <seconds> <message>

# Output step/section headers
dm_step <number> <description>
dm_step <description>               # No step number
dm_banner <title>                   # Large banner
dm_done <title>                     # Success banner

# Assertion counters (automatically incremented)
dm_assert_eq <label> <expected> <actual>
dm_assert_gt <label> <value> <than>
dm_print_summary                    # Print pass/fail; exit 1 if failures exist
```

### Mapping Deployment Requirement

**Important:** All inbound mappings must be explicitly deployed to the connector before activation:

```bash
dm_create_mapping "$MAPPING_JSON"
MAPPING_ID="$_DM_LAST_MAPPING_ID"
dm_deploy_mapping_to_mqtt_connector "$MAPPING_ID"  # <-- Required!
dm_activate_mapping "$MAPPING_ID"
```

Without explicit deployment via `dm_deploy_mapping_to_mqtt_connector()`, the inbound mapping will not receive messages.

### MQTT Broker Configuration

All tests use this default broker configuration:

```bash
MQTT_HOST=${MQTT_HOST:-broker.hivemq.com}
MQTT_PORT=${MQTT_PORT:-1883}
MQTT_TLS=${MQTT_TLS:-false}
MQTT_INSECURE=${MQTT_INSECURE:-true}
```

Override via environment variables:

```bash
export MQTT_HOST=my-broker.example.com
export MQTT_PORT=8883
export MQTT_TLS=true
bash test-inbound-json-smartfunction.sh
```

### Smart Function Test Pattern

Inbound tests using JavaScript transformation follow this pattern:

```bash
SF_CODE=$(cat << 'EOF'
function onMessage(msg, ctx) {
    const sourceObject = msg.getPayload();
    const config = ctx.getConfig ? ctx.getConfig() : {};
    const topicLevels = typeof config.topic === 'string'
        ? config.topic.split('/')
        : (Array.isArray(sourceObject['_TOPIC_LEVEL_']) ? sourceObject['_TOPIC_LEVEL_'] : []);
    
    const externalId = topicLevels.length > 0 
        ? topicLevels[topicLevels.length - 1] 
        : null;
    
    if (!externalId) return [];
    
    return [{
        cumulocityType: 'measurement',
        action: 'create',
        payload: {
            time: new Date().toISOString(),
            type: 'c8y_TemperatureMeasurement',
            c8y_TemperatureMeasurement: {
                T: { value: sourceObject['temperature'], unit: 'C' }
            }
        },
        externalSource: [{ type: 'c8y_Serial', externalId: externalId }]
    }];
}
export { onMessage };
EOF
)
```

**Key points for Smart Functions:**
- Must define `function onMessage(msg, context) { ... }`
- Must call `msg.getPayload()` to access deserialized JSON
- Must return array of Cumulocity objects (even if empty)
- Each object must have `cumulocityType`, `action`, `payload`, and `externalSource`
- Must export function explicitly: `export { onMessage };`
- Topic levels accessible via `config.topic.split('/')` or `sourceObject._TOPIC_LEVEL_` array

### Debugging Failed Tests

1. **Run with `--keep` flag** to preserve test data:
   ```bash
   bash test-inbound-json-smartfunction.sh --keep
   ```

2. **Manually inspect mapping stats:**
   ```bash
   c8y api --method GET --url "service/dynamic-mapper/mapping-stats?mappingId=${MAPPING_ID}" | jq .
   ```

3. **Check connector connection status:**
   ```bash
   c8y api --method GET --url "service/dynamic-mapper/connector/status?connectorId=${_DM_MQTT_CONNECTOR_ID}" | jq .
   ```

4. **List measurements created for device:**
   ```bash
   c8y measurement list --type c8y_TemperatureMeasurement --source ${DEVICE_ID} | jq '.measurements[] | {time, type, c8y_TemperatureMeasurement}'
   ```

5. **View mapping debug logs:**
   - Enable `debug: true` on the mapping in the mapping JSON
   - Logs appear in the microservice logs (run: `c8y microservices logs dynamic-mapper`)

## Test Inventory

| Test | Type | Purpose |
|------|------|---------|
| `test-inbound-json-smartfunction.sh` | Smart Func | JavaScript transformation (temperature JSON → measurement) |
| `test-inbound-json-jsonata.sh` | JSONata | JSONata expression (JSON payload → measurement) |
| `test-inbound-json-default.sh` | Default | No transformation (raw JSON passthrough) |
| `test-inbound-flatfile.sh` | Flat File | CSV parsing via substitution |
| `test-inbound-hex.sh` | Binary HEX | HEX decoding via Smart Function → event |
| `test-inbound-implicit-device.sh` | Implicit Device | Auto-create device from external ID |
| `test-inbound-multi-device.sh` | Array Expansion | JSON array → multiple measurements |
| `test-inbound-http-connector.sh` | HTTP Connector | HTTP webhook inbound transformation |

## Test Execution Order

```
1. Validate environment (tools, service, mqtt connector)
2. Create mapping with specific config (substitutions, smart function, etc.)
3. Deploy mapping to MQTT connector (explicit requirement)
4. Activate mapping (PUT /mapping/{id} with active=true)
5. Record test start time
6. Publish MQTT message
7. Wait for processing (5-10 seconds typical)
8. Verify device/measurement/event created with correct values
9. Cleanup (delete mapping, delete device identity, delete device)
```

## Common Issues & Solutions

### "Device not found" on lookup
- Check external ID was extracted correctly: `c8y identity list --type c8y_Serial | jq . | grep -i dmtest`
- Verify topic levels are correct: `dmtest/template/device123` has levels [0="dmtest", 1="template", 2="device123"]
- Check mapping substitution `pathSource: "_TOPIC_LEVEL_[2]"` targets correct level

### "Measurement count is 0"
- Verify mapping is deployed: `c8y api --url "service/dynamic-mapper/mapping-stats?mappingId=${MAPPING_ID}" | jq '.messagedDelivered'`
- Check connector status: `dm_verify_mqtt_connector_ready`
- Enable debug on mapping (`debug: true`) and check microservice logs

### "MQTT connector not CONNECTED"
- Verify MQTT broker is reachable: `nc -zv broker.hivemq.com 1883`
- Check connector logs: `c8y microservices logs dynamic-mapper | grep -i mqtt`
- Recreate connector: `dm_setup_and_connect_mqtt_connector`

### Script times out
- Increase wait time in test: `dm_wait 10 "for processing"`
- Check microservice performance: `c8y microservices get dynamic-mapper | jq '.resources'`
- Reduce concurrent tests (run only 1 test at a time)

## Contributing New Tests

1. Start from [TEST_TEMPLATE.sh](TEST_TEMPLATE.sh)
2. Customize mapping JSON for your use case
3. Add validation assertions
4. Test with `--keep` flag to verify cleanup
5. Document in test Inventory table above

## See Also

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — Component overview
- [CLAUDE.md](../../CLAUDE.md) — Build commands and backend architecture
- [TEST_CONCEPT.md](../../TEST_CONCEPT.md) — Detailed test planning
- [EXTENSIONS.md](../../EXTENSIONS.md) — Custom extension development
