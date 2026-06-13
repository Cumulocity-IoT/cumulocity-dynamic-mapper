# Enhancement Plan — Run a subset of harness tests against the Cumulocity MQTT Service (Pulsar)

## Objective

Let a **subset** of the bash integration tests (`resources/script/test/`) drive mappings
through a **`CUMULOCITY_MQTT_SERVICE_PULSAR`** connector, while the existing suite keeps
working **unchanged** against the public HiveMQ / EMQX brokers.

## Decisions (locked in)

1. **Auth = X.509 device certificate.** Test MQTT clients authenticate to the MQTT
   Service with a client certificate (the c8y session is OAuth2/token — it has no
   password, so basic auth is out). Trust anchor is uploaded with
   `c8y devicemanagement certificates create`.
2. **Coexistence, not cut-over.** The public-broker path stays the default and is not
   touched. A new opt-in mode runs a representative **subset** against the MQTT Service.
3. **Fail loudly.** When the MQTT Service is unreachable, the Pulsar connector isn't
   connected, or cert provisioning/permission fails, the MQTT-Service tests **FAIL**
   (not skip).

## Correction to an earlier assumption

An earlier draft treated the MQTT Service as an internal Pulsar-only path not drivable
with `mosquitto`. **Wrong.** Per the docs
(https://cumulocity.com/docs/device-integration/mqtt-service/) it exposes a **standard
MQTT interface to clients**; the Pulsar `from-device`/`to-device` topics are internal
plumbing. A test publishes/subscribes with ordinary MQTT, exactly like a device.

## MQTT Service client facts (from the docs)

| Aspect | Value / rule | Harness impact |
|---|---|---|
| Endpoint | `<tenant-domain>` | `MQTT_HOST` |
| Port / TLS | **9883 (TLS)**; 2883 non-TLS disabled on shared public | `MQTT_PORT=9883`, `MQTT_TLS=true` |
| Auth (chosen) | **X.509**: cert CN **must equal** the MQTT clientId; **tenant ID in the username field**; trust anchor configured in tenant | mosquitto `--cert/--key`, `-i <CN>`, `-u <tenant>` |
| Protocol | 3.1.1 / 5.0 | fine |
| QoS | 0 and 1 only — **no QoS 2** | drop/adjust QoS-2 cases |
| Retained | **rejected** (connection closed) | never `mosquitto_pub -r` |
| Clean session | **required** (=1) | mosquitto default OK |
| Wildcards | `+`/`#` on non-reserved topics (`supportsWildcardInTopicInbound=true`) | existing wildcard mappings OK |
| Reserved topics | `$...` and Core-MQTT (`s/`,`t/`,`measurement/measurements/`,…) off-limits | keep `dmtest/...` (already safe) |

## Resolved config for the current test tenant (`ck4` / `t2050305588`)

| Setting | Value |
|---|---|
| `MQTT_HOST` | `ck4.eu-latest.cumulocity.com` |
| `MQTT_PORT` / TLS | `9883` / true |
| Tenant (username) | `t2050305588` |
| clientId / cert CN | a test-specific id, e.g. `dmtest-mqtt-<run>` |
| Server CA | publicly-trusted `*.cumulocity.com` → system CA bundle (`MQTT_CAFILE`/`--capath` as needed) |

## Certificate auth workflow (the new bit)

`c8y devicemanagement certificates create --name <n> --file <pem>` uploads a **trusted
(trust-anchor) certificate**; a client presenting a cert that matches / is signed by it
authenticates. Simplest path = a **self-signed** cert used directly as the client cert:

1. **Generate** a self-signed cert+key with `CN=<clientId>` (openssl):
   `openssl req -x509 -newkey rsa:2048 -nodes -keyout dev.key -out dev.pem -days 2 -subj "/CN=<clientId>"`
2. **Upload as trusted** (optionally auto-register the device):
   `c8y devicemanagement certificates create --name <clientId> --file dev.pem --autoRegistrationEnabled`
3. **Publish/subscribe** with the client cert against `:9883`:
   `mosquitto_pub -h <host> -p 9883 --cert dev.pem --key dev.key -u <tenant> -i <clientId> -t dmtest/... -m '{...}' -q 1`
4. **Teardown:** delete the uploaded trusted certificate (and the auto-registered device,
   if any) on exit.

Open spike items for this workflow (verify in Phase 0, don't block the plan):
- Whether a self-signed cert uploaded directly is accepted, or a CA→leaf chain is
  required (CN=clientId on the leaf).
- Whether mosquitto needs an explicit `--cafile`/`--capath` for server verification on
  the runner (macOS vs Linux).
- Whether `--autoRegistrationEnabled` is needed, or the mapping's
  `createNonExistingDevice` + inbound device resolution is enough.

## Inbound / outbound message flow (unchanged conceptually)

- **Inbound:** cert-authenticated `mosquitto_pub` → MQTT Service → `from-device` → the
  Pulsar connector → C8Y. Verify the created measurement/event/alarm as today.
- **Outbound:** mapper → `to-device` → MQTT Service → cert-authenticated
  `mosquitto_sub` verifies receipt as today.

## Status of the foundation (already done)

- ✅ `test-cumulocity-mqtt-service.sh` — connector lifecycle (delete → create with full
  default properties → connect → disconnect), under `reliability`.
- ✅ Harness helpers: `dm_connector_default_properties`,
  `dm_setup_c8y_mqtt_service_connector`, `dm_delete_connector`,
  `dm_list_connector_ids_by_type`, `dm_wait_for_connector_status`, `dm_assert_ne`,
  `dm_enable/disable_connector` (full-config round-trip), and the `</dev/null`+`--force`
  fixes in `dm_api`.
- ✅ `dm_mqtt_publish`/`dm_mqtt_subscribe_one` already honour
  `MQTT_HOST/PORT/USER/PASS/TLS/CAFILE/INSECURE` — cert flags are the main addition.

## Plan (phased)

**Phase 0 — Spike — ✅ DONE (inbound round-trip green):**
- `test-c8y-mqtt-service-spike.sh` automates the inbound round-trip: validate tools +
  reachability of `:9883` + CA bundle → resolve/connect a `CUMULOCITY_MQTT_SERVICE_PULSAR`
  connector → generate a self-signed cert (CN=clientId) and upload it via
  `c8y devicemanagement certificates create --autoRegistrationEnabled` → create the device
  (external id `c8y_Serial`=clientId) → deploy (**verified**) + activate a JSON/DEFAULT→
  MEASUREMENT mapping → cert-authenticated `mosquitto_pub` to `:9883` → assert the
  measurement was created. Fails loudly at each gate.
- New harness helpers: `dm_provision_mqtt_service_cert`, `dm_cleanup_mqtt_service_cert`,
  `dm_ca_bundle`.
- Run: `bash test-c8y-mqtt-service-spike.sh` (add `--keep` to inspect artifacts).
- **Root cause of the long-running "no measurement" failure (resolved):** the mapping was
  resolved but bound to NO connector ("No active connector" in the UI). `PUT
  /deployment/defined/{id}` takes a top-level array body `["<conn>"]`; the generic
  `dm_api_must` path serialized it via `--template input.value`, which the installed
  go-c8y-cli mangled so the deployment registered no connector (PUT still 2xx). Fixed:
  `dm_deploy_mapping_to_connector` now PUTs a **literal** `--template "[\"<conn>\"]"` and
  **verifies** the connector is present, failing loudly otherwise. Not a backend bug.
- Resolved open items: self-signed-as-trust-anchor cert works directly; `--cafile` from the
  system CA bundle is needed on macOS; the device is resolved from the topic external id
  independent of the publishing client identity.
- **Outbound round-trip (`mosquitto_sub`) remains** — fold into the Phase 2 outbound test.

**Phase 1 — Harness plumbing — ✅ IMPLEMENTED (additive, public mode untouched):**
4. ✅ `DM_BROKER_MODE=public|c8y-mqtt-service` (default `public`). In c8y-mqtt-service mode
   the harness presets `MQTT_HOST/PORT/TLS` from `DM_C8Y_MQTT_*` / `C8Y_DOMAIN`;
   `_dm_require_mqtt_service_broker` is authoritative.
5. ✅ `dm_provision_mqtt_service_cert` / `dm_cleanup_mqtt_service_cert` exist; cert teardown
   is now wired into `_dm_on_exit` (runs on exit whenever a cert was provisioned, honours
   `--keep`), so migrated tests need no extra cleanup wiring.
6. ✅ `dm_mqtt_publish`/`dm_mqtt_subscribe_one` add `--cert/--key/-i <CN>/-u <tenant>` in
   c8y-mqtt-service mode via `_dm_mqtt_append_auth_args` (public mode unchanged).
7. ✅ `dm_require_mqtt_broker` branches: c8y mode requires the singleton Pulsar connector
   CONNECTED (auto-connects it; **fails loudly** if absent/not connected), provisions a
   run-unique cert, and sets `_DM_MQTT_CONNECTOR_ID`. `dm_deploy_mapping_to_connector` /
   `dm_deploy_mapping_to_mqtt_connector` deploy to it (both verified).
8. ✅ `_dm_mqtt_guard_qos` rejects QoS 2 in c8y mode; `dm_mqtt_publish` has no retained flag.
   **Constraint:** in c8y mode the clientId is fixed to the cert CN, so only one mosquitto
   client (pub OR sub) may connect at a time — fine for the inbound (pub-only) and outbound
   (sub-only) subset, but a test that publishes and subscribes concurrently would need a
   second cert.

**Phase 2 — Migrate a representative subset — ✅ IMPLEMENTED:**
9. The subset runs in BOTH modes **with no per-file edits** — all broker-specific
   behaviour lives in the Phase 1 harness branches:
   - `test-inbound-json-default` (inbound MEASUREMENT — publish-only)
   - `test-outbound-measurement` (outbound; asserts `messagesReceived`, broker-agnostic)
   - `test-outbound-static-subscription` (subscription mgmt; broker-independent)
   The only enabling harness change was guarding `dm_assert_mqtt_topics_active` to a
   CONNECTED-only check in c8y mode (the Pulsar connector consumes one internal topic,
   so the per-MQTT-topic subscription count doesn't apply).
10. `run-tests.sh` takes **two parameters: `[SUITE] [CONNECTOR]`** — the suite
    (category / indices / script name) and the connector: `g` = generic MQTT (public,
    default) or `m` = Cumulocity MQTT Service (`CUMULOCITY_MQTT_SERVICE_PULSAR`, sets
    `DM_BROKER_MODE=c8y-mqtt-service`). The `g`/`m` token may appear in any position;
    interactive mode prompts for both. E.g. `./run-tests.sh inbound m`,
    `./run-tests.sh all g`. In `m` mode the first test provisions/connects the shared
    Pulsar connector (`dmmqttsvc` by default, or `DM_C8Y_MQTT_CONNECTOR_ID`); the rest
    reuse it; each provisions its own run-unique cert (cleaned up on exit).

**Phase 3 — Document + wire up — ✅ IMPLEMENTED:**
11. ✅ `run-tests.sh` two-param interface (`[SUITE] [CONNECTOR]`) + `DM_BROKER_MODE` env docs.
12. ✅ `README.md` → new section **"Running against the Cumulocity MQTT Service"**: connector
    selection, the automatic connector/cert/teardown flow, prerequisites, the MQTT Service
    constraints table, and the migrated subset.
13. ✅ Outbound `mosquitto_sub` broker-receipt check: `test-outbound-topic-resolution`
    already subscribes (best-effort) to verify the broker round-trip; with cert auth in the
    harness it now exercises **real MQTT Service delivery** in `m` mode. Its miss-message is
    mode-aware and points at the one remaining open question below. (Kept best-effort, not a
    hard assertion, precisely because that question is unresolved.)

**Open question surfaced by the outbound round-trip:** MQTT Service delivery appears to be
scoped to the **publishing device's identity**, so a separate cert-authenticated test
subscriber (different CN) may not receive a message the mapper published for another device.
To assert outbound receipt hard, the subscriber likely has to authenticate as (a cert whose
CN maps to) the target device. Resolving this is the next concrete spike if hard outbound
verification is wanted.

## Remaining open items (non-blocking; resolved in the spike)

- Exact certificate model (self-signed-as-trusted vs CA+leaf) and `--cafile` need.
- Device-source attribution under `deviceIsolationMQTTServiceEnabled` — does inbound
  mapping resolve the device from topic/payload as today, independent of the publishing
  MQTT client identity?
- Final list of subset tests (the three above are a proposal).

## Risks

- Principal lacks the *Mqtt service* permission or cert-upload rights → message tests
  fail at connect (by design — fail loudly).
- Singleton connector: parallel runs on one tenant contend for it (lifecycle test already
  deletes/recreates it).
- CI egress to `:9883` blocked → c8y-mqtt-service mode unusable there.
- Token expiry (~48h on this session) only affects the c8y REST calls, not the cert MQTT
  auth — but a stale session fails everything regardless.

## Out of scope

- Backend hardening fixes (delete teardown / status null-guard) — deploy separately.
- Restoring the tenant's pre-existing MQTT Service connector after a run.
- Basic-auth and token-as-password MQTT auth (rejected in favour of X.509).
