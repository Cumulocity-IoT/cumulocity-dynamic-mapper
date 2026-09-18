# Dynamic Mapper Integration Tests

This directory contains bash-based integration tests for the Dynamic Mapper's inbound and outbound transformation pipelines. By default they use the public HiveMQ MQTT broker; the same MQTT tests can also be driven through the **Cumulocity MQTT Service** (`CUMULOCITY_MQTT_SERVICE_PULSAR` connector, X.509 cert auth) — see [Running against the Cumulocity MQTT Service](#running-against-the-cumulocity-mqtt-service).

## Quick Start

```bash
# Validate environment only — exits before any test data is created
bash test-inbound-json-smartfunction.sh --validate-only

# Run a single test (created data is cleaned up by default)
bash test-inbound-json-smartfunction.sh

# Keep created test data for debugging (skip cleanup)
bash test-inbound-json-smartfunction.sh --keep

# Run all inbound tests
bash run-tests.sh inbound

# Run all tests
bash run-tests.sh

# Pick suite + connector: g = generic MQTT (default), m = Cumulocity MQTT Service
bash run-tests.sh inbound m       # inbound suite against the MQTT Service
bash run-tests.sh all g           # everything against the public broker
```

The runner takes two parameters — `[SUITE] [CONNECTOR]`. See
[Running against the Cumulocity MQTT Service](#running-against-the-cumulocity-mqtt-service).

### Standard flags

Every test script accepts the same flags (parsed by `dm_parse_args` in the
harness):

| Flag | Effect |
|------|--------|
| _(none)_ / `--cleanup` | Run the test and delete created data on exit (default). |
| `--keep` | Run the test but retain created data for post-mortem debugging. |
| `--validate-only` | Run environment validation, then exit `0` before creating data. |

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
dm_assert_mqtt_topics_active "$MAPPING_ID"         # <-- Verifies THIS topic is subscribed
```

Without explicit deployment via `dm_deploy_mapping_to_mqtt_connector()`, the inbound mapping will not receive messages.

**Always pass the mapping id to `dm_assert_mqtt_topics_active`.** It then resolves the
mapping's `mappingTopic` and requires *that* topic to appear in
`GET /monitoring/subscription/{connector}` with a count > 0. Called without an id it
only checks that *some* topic is subscribed — which any unrelated active mapping in the
tenant satisfies, so a test could publish into a topic the connector had never
subscribed and fail 20s later on its result assertion instead.

`dm_mqtt_publish` re-checks that the connector is `CONNECTED` immediately before
publishing (polling up to 15s for a reconnect) and publishes at **QoS 1** by default, so
the broker acknowledges the message. A connector that was connected at activation time
can still have dropped by the time the payload goes out — the broker then silently
discards it. Set `_DM_MQTT_SKIP_CONNECTOR_CHECK=true` for a publish that must not abort
the run.

### MQTT Broker Configuration

All tests use this default broker configuration:

```bash
MQTT_HOST=${MQTT_HOST:-broker.hivemq.com}
MQTT_PORT=${MQTT_PORT:-1883}
MQTT_TLS=${MQTT_TLS:-false}
MQTT_INSECURE=${MQTT_INSECURE:-false}
```

Override via environment variables:

```bash
export MQTT_HOST=my-broker.example.com
export MQTT_PORT=8883
export MQTT_TLS=true
bash test-inbound-json-smartfunction.sh
```

## Running against the Cumulocity MQTT Service

The MQTT tests can run against the **Cumulocity MQTT Service** instead of a public
broker. The service exposes a standard MQTT interface to clients (TLS port **9883**)
and is backed by a `CUMULOCITY_MQTT_SERVICE_PULSAR` connector inside the mapper. The
public-broker path is unchanged and remains the default — this is an additive,
opt-in mode.

### Selecting the connector

Pick the broker with the runner's second parameter, or with `DM_BROKER_MODE`:

```bash
# via run-tests.sh — second token g (generic) | m (Cumulocity MQTT Service)
bash run-tests.sh inbound m
bash run-tests.sh 1 3 5 m          # menu items 1/3/5 against the MQTT Service

# via environment (a single script, or your own lane)
export DM_BROKER_MODE=c8y-mqtt-service
bash test-inbound-json-default.sh
```

| `DM_BROKER_MODE` | Broker | Auth |
|---|---|---|
| `public` _(default)_ | public HiveMQ/EMQX (or your `MQTT_HOST`) | `MQTT_USER`/`MQTT_PASS` (optional) |
| `c8y-mqtt-service` | Cumulocity MQTT Service on `:9883` (TLS) | X.509 client certificate |

In `c8y-mqtt-service` mode the harness presets the endpoint from the active c8y
session (`MQTT_HOST=$C8Y_DOMAIN`, `MQTT_PORT=9883`, `MQTT_TLS=true`) — override with
`DM_C8Y_MQTT_HOST` / `DM_C8Y_MQTT_PORT` if needed.

### How it works (handled automatically by the harness)

`dm_require_mqtt_broker` (called by every MQTT test) does the following in
`c8y-mqtt-service` mode:

1. **Connector** — resolves the singleton `CUMULOCITY_MQTT_SERVICE_PULSAR` connector,
   **creating** it (`dmmqttsvc`, or `DM_C8Y_MQTT_CONNECTOR_ID`) if absent, and ensures
   it is `CONNECTED`.
2. **Certificate** — generates a self-signed cert (`CN == clientId`) and uploads it as
   a trusted (trust-anchor) certificate via
   `c8y devicemanagement certificates create --autoRegistrationEnabled`.
3. **Publish/subscribe** — `dm_mqtt_publish` / `dm_mqtt_subscribe_one` automatically add
   `--cert/--key`, `-i <clientId>` and `-u <tenant>`.
4. **Teardown** — the trust anchor is deleted on exit (honours `--keep`).

So the test scripts themselves need no changes — the same file runs against either
broker.

### Prerequisites

- An active **c8y session** (`C8Y_DOMAIN` / `C8Y_TENANT` exported) with the
  **_Mqtt service_** permission and the right to manage trusted certificates.
- `mosquitto_pub` / `mosquitto_sub`, `openssl`, and a system **CA bundle** for TLS
  server verification (auto-discovered; set `MQTT_CAFILE` to override, or
  `MQTT_INSECURE=true` to skip — not recommended).
- Network egress to `<tenant-domain>:9883`.

If any of these is missing the MQTT-Service tests **fail loudly** (they do not skip),
so a misconfiguration is never silently ignored.

### Constraints of the MQTT Service (vs a public broker)

| Aspect | Rule |
|---|---|
| Port / TLS | `9883`, TLS required (no plaintext on shared public tenants) |
| Auth | X.509 cert; **cert CN must equal the MQTT clientId**; tenant id in the username |
| QoS | **0 and 1 only** — QoS 2 is rejected (the harness fails fast on QoS 2) |
| Retained | not allowed |
| Clean session | required |
| Reserved topics | `$...` and Core-MQTT (`s/`, `t/`, `measurement/…`) are off-limits — tests use `dmtest/...` |
| Concurrent clients | the clientId is fixed to the cert CN, so only **one** mosquitto client (publish *or* subscribe) at a time per run |

### Migrated subset

These tests are verified to run against **both** brokers with no per-file changes:

- `test-inbound-json-default` — inbound JSON → MEASUREMENT (cert-authenticated publish)
- `test-outbound-measurement` — outbound MEASUREMENT (asserts the mapper processed it)
- `test-outbound-static-subscription` — outbound subscription management
- `test-outbound-topic-resolution` — outbound EVENT with a dynamic topic; also
  subscribes with `mosquitto_sub` to verify the **actual broker round-trip** (in `m`
  mode this exercises real MQTT Service delivery — best-effort, see *Outbound receipt*
  below)

Other tests may run against `m` too, but those that need broker-specific features
(HTTP connector, Kafka/Sparkplug extensions, multi-connector) are not expected to pass.

For bringing MQTT Service up on a new tenant, or diagnosing cert / reachability /
connector problems, run [diagnose-mqtt-service-setup.sh](diagnose-mqtt-service-setup.sh) —
a deliberately verbose, self-contained probe of the full cert-authenticated round trip.
It is a diagnostic, not a regression test, so it is not part of `run-tests.sh`.

### Known traps

**Outbound receipt is asserted best-effort.** MQTT Service delivery appears to be scoped to
the publishing device's identity: a separate cert-authenticated subscriber (a different CN) may
not receive a message the mapper published for another device. So
`test-outbound-topic-resolution` and `test-outbound-smartfunction-externalsource` **warn**
rather than fail when no message is captured — the assertion that the mapping *processed* the
notification is still hard. Asserting receipt hard would require the subscriber to authenticate
as a cert whose CN maps to the target device.

**Deployment must be verified, not assumed.** `PUT /deployment/defined/{id}` takes a top-level
array body `["<connector>"]`. Serializing that through the generic `--template input.value` path
gets mangled by go-c8y-cli, so the deployment registers **no connector** while the PUT still
returns 2xx — the mapping then shows "No active connector" and inbound messages are dropped by
the route filter. `dm_deploy_mapping_to_connector` therefore PUTs a literal
`--template "[\"<connector>\"]"` and verifies the connector is present afterwards, failing
loudly if not. This is a CLI serialization trap, not a backend bug.

**The connector is a singleton per tenant.** Because the clientId is pinned to the cert CN,
parallel runs on one tenant contend for the same connector. Run them serially.

**CI egress to `:9883` must be open**, or `m` mode is unusable there.

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
EOF
)

# Append `export { onMessage }` only when the tenant runs in ESM mode:
SF_CODE=$(dm_wrap_onmessage_code "$SF_CODE")
```

**Key points for Smart Functions:**
- Must define `function onMessage(msg, context) { ... }`
- Must call `msg.getPayload()` to access deserialized JSON
- Must return array of Cumulocity objects (even if empty)
- Each object must have `cumulocityType`, `action`, `payload`, and `externalSource`
- The `export { onMessage };` line is **only** required in ESM mode. Don't hardcode
  it — wrap the source with `dm_wrap_onmessage_code` (in the harness), which appends
  the export only when the tenant's `supportESM` is `true`.
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

The authoritative catalogue lives in [run-tests.sh](run-tests.sh) (the `TESTS`
array) and drives the interactive menu. The categories below mirror it:

### Inbound (payload)
| Test | Purpose |
|------|---------|
| `test-inbound-json-default` | JSON / DEFAULT → MEASUREMENT |
| `test-inbound-json-jsonata` | JSON / JSONATA → EVENT |
| `test-inbound-json-smartfunction` | JSON / Smart Function → MEASUREMENT |
| `test-inbound-flatfile` | FLAT_FILE / CSV → MEASUREMENT |
| `test-inbound-hex` | HEX → EVENT |
| `test-inbound-http-connector` | HTTP connector → MEASUREMENT |
| `test-inbound-implicit-device` | Implicit device auto-creation |
| `test-inbound-implicit-device-recreate-after-delete` | Regression: an implicit device is recreated after the original is deleted from inventory (stale-cache bug) |
| `test-inbound-multi-device` | Array payload → multiple devices |
| `test-inbound-alarm` | JSON / DEFAULT → ALARM |
| `test-inbound-operation` | JSON / DEFAULT → OPERATION |
| `test-inbound-inventory` | JSON / DEFAULT → INVENTORY — updates **device metadata** (type + custom fragment) on an existing managed object (`updateExistingDevice: true`) |

### Inbound (Smart Function patterns)
| Test | Purpose |
|------|---------|
| `test-inbound-smartfunction-02` | Topic-based external ID + sensor filter |
| `test-inbound-smartfunction-03` | `context.getManagedObjectByExternalId` — enrich an event payload from the managed object |
| `test-inbound-smartfunction-04` | Dual payload type + deduplication |
| `test-inbound-smartfunction-05` | Per-device running statistics keyed on the MQTT client ID from context |

### Inbound (Java extensions)
| Test | Purpose |
|------|---------|
| `test-inbound-extension-custom-measurement` | Extension: JSON → Measurement |
| `test-inbound-extension-custom-alarm` | Extension: JSON → Alarm |
| `test-inbound-extension-custom-event` | Extension: Protobuf → Event |
| `test-inbound-extension-sparkplugb-measurement` | Extension: Sparkplug B → Measurement |

### Outbound (payload + subscriptions)
| Test | Purpose |
|------|---------|
| `test-outbound-measurement` | C8Y Measurement → MQTT broker |
| `test-outbound-event` | C8Y Event → MQTT broker |
| `test-outbound-alarm` | C8Y Alarm → MQTT broker |
| `test-outbound-operation` | C8Y Operation → MQTT broker |
| `test-outbound-inventory` | C8Y managed-object change (**device metadata**) → MQTT broker |
| `test-outbound-filter` | `filterMapping` — selective forwarding |
| `test-outbound-topic-resolution` | Dynamic publish topic resolution |
| `test-outbound-json-smartfunction` | Smart Function: Measurement → MQTT JSON |
| `test-outbound-smartfunction-externalsource` | Smart Function `externalSource` resolves the `_externalId_` topic token — verified by a real broker round-trip (`mosquitto_sub -v`) |
| `test-outbound-smartfunction-molookup` | Smart Function `getManagedObjectByExternalId` — a C8Y measurement notification is enriched with managed-object properties before publishing |
| `test-outbound-static-subscription` | Static subscription management |
| `test-outbound-type-subscription` | Dynamic type subscription |
| `test-outbound-group-subscription` | Dynamic group subscription |
| `test-outbound-group-subscription-removal` | Group subscription removal |
| `test-outbound-subscription-persistence` | Subscription persistence after restart |
| `test-outbound-extension-alarm-to-sparkplugb` | Extension: Alarm → Sparkplug B DCMD |

### Reliability
| Test | Purpose |
|------|---------|
| `test-multi-tenant` | Mapping CRUD / tenant isolation |
| `test-multi-connector` | Multiple connector status check |
| `test-reconnect` | Connector disconnect / reconnect cycle |
| `test-cumulocity-mqtt-service` | Cumulocity MQTT Service connector: delete → create → connect → disconnect |

> **Note:** `test-outbound-group-subscription` hands off state to
> `test-outbound-group-subscription-removal`; when run via `run-tests.sh` the
> former is invoked with `--keep` so its group/device survive for the latter.

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
- Confirm the topic is really subscribed: `dm_assert_mqtt_topics_active "$MAPPING_ID"` —
  and check the microservice log for a `Connection state changed` line around the
  publish. A connector that reconnects during the test loses its subscriptions, and the
  published message is dropped by the broker.
- Enable debug on mapping (`debug: true`) and check microservice logs

### "type-based subscription exists: 0 is not > 0"

- `dm_set_type_subscriptions` now verifies the write by reading it back, so a type filter
  that never lands fails at step 1 instead of looking like broken device discovery. If it
  aborts there, compare the two diagnostics it prints: the mapper's
  `GET /subscription/type` answer and the raw C8Y management subscription.
- A mapper response of `{}` means the tenant's `DynamicMapperManagementSubscription`
  (context `tenant`) exists **without** a `typeFilter` — the PUT's replacement had not
  become visible yet, or never happened.
- Assert membership with `dm_assert_type_subscription_present <label> <type> [timeout]`,
  which polls. A single read right after the PUT can still see the pre-delete state.
- In a cleanup path use `_DM_TYPE_SUB_VERIFY=false` so a failed clear warns instead of
  aborting the trap.

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

- [architecture.md](../../../docs/architecture.md) — Component overview
- [CLAUDE.md](../../../CLAUDE.md) — Build commands and backend architecture
- [testing.md](../../../docs/testing.md) — Detailed test planning
- [extensions.md](../../../docs/extensions.md) — Custom extension development
