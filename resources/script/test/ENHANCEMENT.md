# Cumulocity MQTT Service test mode — Design & decisions

Design record for running the bash integration tests against the **Cumulocity MQTT
Service** (`CUMULOCITY_MQTT_SERVICE_PULSAR` connector) in addition to the public broker.

**Status: implemented.** For *how to use it* see the README section
[Running against the Cumulocity MQTT Service](README.md#running-against-the-cumulocity-mqtt-service).
This file captures the rationale, the non-obvious findings, and the one open question.

## Decisions (locked in)

1. **Auth = X.509 device certificate.** The c8y session is OAuth2/token — it has no
   password, so basic auth is out. Test clients present a self-signed client cert whose
   CN equals the MQTT clientId; it is uploaded as a trust anchor with
   `c8y devicemanagement certificates create`. (A self-signed cert used directly as the
   client cert is accepted — no CA→leaf chain needed.)
2. **Coexistence, not cut-over.** The public-broker path stays the default and is
   untouched. The MQTT Service is an additive, opt-in mode (`DM_BROKER_MODE` / the `m`
   connector token).
3. **Fail loudly.** When the MQTT Service is unreachable, the connector won't connect, or
   cert provisioning/permission fails, the MQTT-Service tests **FAIL** (they do not skip).

## What it does

The MQTT Service exposes a standard MQTT interface to clients (TLS `:9883`); the Pulsar
`from-device`/`to-device` topics are internal plumbing. Tests publish/subscribe with
ordinary MQTT, exactly like a device:

- **Inbound:** cert-authenticated `mosquitto_pub` → MQTT Service → Pulsar connector → C8Y;
  assert the created measurement/event/alarm as today. The device is resolved from the
  topic/payload external id, **independent of the publishing client's cert identity**.
- **Outbound:** mapper → MQTT Service → cert-authenticated `mosquitto_sub`.

All broker-specific behaviour lives in `test-harness.sh` (mode branches in
`dm_require_mqtt_broker`, `dm_mqtt_publish`/`dm_mqtt_subscribe_one`, cert provisioning,
QoS guard), so the test scripts run against either broker **with no per-file changes**.
The migrated/verified subset and the runner's two-parameter interface are documented in
the README.

## Non-obvious findings (resolved)

- **Deployment must be verified, not assumed.** `PUT /deployment/defined/{id}` takes a
  top-level array body `["<conn>"]`. The generic `dm_api_must` path serialized it via
  `--template input.value`, which the installed go-c8y-cli mangled so the deployment
  registered **no connector** (PUT still returned 2xx) → mapping shows "No active
  connector" and inbound messages are dropped by the route filter. Fix:
  `dm_deploy_mapping_to_connector` PUTs a **literal** `--template "[\"<conn>\"]"` and
  verifies the connector is present, failing loudly otherwise. Not a backend bug.
- **TLS server verification** needs a system CA bundle (`--cafile`) on macOS;
  auto-discovered by `dm_ca_bundle`, overridable via `MQTT_CAFILE`.
- **Singleton clientId == cert CN** ⇒ only one mosquitto client (publish *or* subscribe)
  per run. Fine for the pub-only inbound and sub-only outbound subset.

## Open question

**MQTT Service outbound delivery appears scoped to the publishing device's identity.** A
separate cert-authenticated test subscriber (different CN) may not receive a message the
mapper published for another device — so the outbound broker-receipt check
(`test-outbound-topic-resolution`) is intentionally **best-effort** (warn, not a hard
assertion). To assert outbound receipt hard, the subscriber likely has to authenticate as
(a cert whose CN maps to) the target device. Resolving this is the next concrete spike if
hard outbound verification is wanted.

## Risks

- Principal lacks the *Mqtt service* permission or cert-upload rights → tests fail at
  connect (by design — fail loudly).
- Singleton connector: parallel runs on one tenant contend for it.
- CI egress to `:9883` blocked → the mode is unusable there.
- c8y session/token expiry fails the REST calls (not the cert MQTT auth, but a stale
  session fails everything regardless).

## Out of scope

- Backend hardening fixes (delete teardown / status null-guard) — deployed separately.
- Restoring a tenant's pre-existing MQTT Service connector after a run.
- Basic-auth / token-as-password MQTT auth (rejected in favour of X.509).
