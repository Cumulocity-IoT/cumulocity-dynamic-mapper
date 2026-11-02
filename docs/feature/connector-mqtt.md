# Connector: MQTT

The generic MQTT connector lets Dynamic Mapper connect to any external MQTT 3.1.1 or
5.0 broker (public or self-hosted) for both inbound (device → Cumulocity) and outbound
(Cumulocity → device) mappings. For the connector-agnostic framework this builds on
(`AConnectorClient`, `ConnectorSpecification`, message dispatch), see
[connector-framework.md](connector-framework.md). For Cumulocity's own managed MQTT
Service offering, see [connector-mqtt-service.md](connector-mqtt-service.md) — despite
the similar name it is a different code path, not a configuration variant of this
connector.

---

## Requirements

**What it is for.** Connecting a tenant to any MQTT 3.1.1 or 5.0 broker, in both directions. This
is the reference connector: the QoS model and the topic/wildcard semantics used throughout the
product are MQTT's.

- **Both directions**, and both protocol versions, selected per connector.
- **Wildcards are supported** in inbound mapping topics (`+`, `#`) with MQTT's own matching rules.
  Outbound publish topics must be concrete — a wildcard there is resolved from the message.
- **All three QoS levels are honoured**, on subscription and on publish. A topic shared by several
  mappings is subscribed at the strongest level any of them asks for; see
  [reliability.md](reliability.md).
- **TLS with a self-signed or private CA certificate must be possible**, since brokers are
  frequently not publicly trusted.
- **A processing failure must not silently drop the message** at QoS > 0: the connector withholds
  the acknowledgement so the broker redelivers. The poison-pill guard (`maxProcessingAttempts`) bounds the
  redelivery.
- **Sparkplug B is supported** as a payload convention on top of MQTT, including the host
  application's birth/death certificate lifecycle.

---

## Implementation

### Class hierarchy and library

[`AMQTTClient`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/mqtt/AMQTTClient.java)
(abstract, ~680 lines) extends `AConnectorClient` and implements everything
version-agnostic: connection lifecycle orchestration, QoS adjustment, config
validation, wildcard/direction support, TLS setup, and Sparkplug B Host support. It
delegates protocol-version-specific mechanics to abstract hooks (`buildMqttClient()`,
`createMqttCallback()`, `connectMqttWithRetry()`, `disconnectMqttClient()`, etc.),
implemented by:

- [`MQTT3Client`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/mqtt/MQTT3Client.java) — MQTT 3.1.1
- [`MQTT5Client`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/mqtt/MQTT5Client.java) — MQTT 5.0 (adds subscribe/unsubscribe reason-code logging and session-present detection that MQTT3 has no equivalent of)

Both are built on the **HiveMQ MQTT Client** library (`com.hivemq:hivemq-mqtt-client`,
declared in `dynamic-mapper-service/pom.xml`), not Eclipse Paho.
`MQTT3Client.java` imports `com.hivemq.client.mqtt.mqtt3.*`; `MQTT5Client.java` imports
`com.hivemq.client.mqtt.mqtt5.*`.

### Configuration (`ConnectorSpecification`)

Built in
[`AMQTTClient.buildCommonMqttProperties()`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/mqtt/AMQTTClient.java),
shared by both version-specific clients:

| Property | Type | Required | Default | Notes |
|---|---|---|---|---|
| `version` | option | yes | `3.1.1` / `5.0` | options `3.1.1` and `5.0`; the default is set by the client class that built the specification, and `ConnectorClientFactory` picks `MQTT3Client` or `MQTT5Client` from the stored value (default `3.1.1`) |
| `protocol` | option | yes | `mqtt://` | `mqtt://`, `mqtts://`, `ws://`, `wss://` |
| `mqttHost` | string | yes | — | |
| `mqttPort` | numeric | yes | — | |
| `user` / `password` | string / sensitive | no | — | |
| `clientId` | string | yes | — | |
| `trustCustomCertificate` | boolean | no | `false` | shown only for `mqtts`/`wss` |
| `nameCertificate`, `trustedCertificateFingerprint`, `certificateChainInPemFormat` | string / large text | no | — | shown only when `trustCustomCertificate=true`; the certificate comes from the PEM chain or from the tenant trusted-certificate store (by fingerprint) |
| `disableHostnameValidation` | boolean | no | `false` | same condition |
| `supportsWildcardInTopicInbound` | boolean | no | `true` | |
| `supportsWildcardInTopicOutbound` | boolean | no | `true` | |
| `serverPath` | string | no | — | shown only for `ws`/`wss` |
| `cleanSession` | boolean | no | `true` | MQTT3 `.cleanSession()`, MQTT5 `.cleanStart()` |
| `reconnectOnProcessingError` | boolean | no | `false` | shown only when `cleanSession=false`; controls QoS 1+ retransmission behavior on error |
| `isSparkplugHost` | boolean | no | `false` | enables Sparkplug B Host mode |
| `sparkplugHostId` | string | no | — | shown only when `isSparkplugHost=true`; falls back to the tenant ID when empty |

The only sensitive property is `password`. Properties are grouped in the UI (`CONNECTION`, `AUTHENTICATION`, `SESSION`, `TLS`, `ADVANCED`, `RELIABILITY`, `SPARKPLUG`).

Spec name/type: `MQTT3Client` registers as `ConnectorSpecificationBuilder.create("MQTT", ConnectorType.MQTT)`;
`MQTT5Client` as `create("MQTT 5.0", ConnectorType.MQTT)` — both share the same
`ConnectorType.MQTT`. `singleton = false` — multiple MQTT connector instances per
tenant are allowed.

### Connection lifecycle

- `connect()`/`disconnect()` are orchestrated in `AMQTTClient`
  and delegate the actual HiveMQ client build/connect/disconnect calls to the
  version-specific subclass.
- Reconnect scheduling lives entirely in `connectorSpecificHousekeeping()`
  (in `MQTT3Client` and `MQTT5Client`, identical logic): if the
  client is disconnected and should be connected, it computes a **linear** backoff
  (`attempt * RECONNECT_DELAY_STEP_MS`, step 10 s, capped at `RECONNECT_DELAY_MAX_MS`, 5
  minutes) and resubmits `connect()` on the virtual-thread pool. Disconnect listeners
  only record the drop and set the next retry time; they do not themselves trigger
  reconnection — housekeeping owns all reconnect scheduling (explicit code comment).
- `AtomicInteger reconnectAttempt`, `AtomicLong nextReconnectTimeMs`/`connectGeneration`
  guard against races from a prior (superseded) connection's callbacks firing after a
  new connect attempt has started — the result of historical lifecycle/concurrency bug
  fixes (commits `dc098d56e`, `ff1622522`, `2815e8bd1`).

### QoS and delivery

- `Qos` enum (`AT_MOST_ONCE`, `AT_LEAST_ONCE`, `EXACTLY_ONCE`) maps directly to MQTT QoS
  0/1/2 via `MqttQos.fromCode(qos.ordinal())`.
- `AMQTTClient` keeps the default `supportedQos` of `AConnectorClient` (all three levels), so
  `adjustQos()` never downgrades; publish uses `effectivePublishQos(context)`.
- Effective QoS for inbound delivery is `min(publish QoS, mapping QoS)`
  (`AbstractMqttCallback`).
- QoS 0 messages ack immediately with no reliability guarantee; QoS 1/2 messages are
  acked manually after successful processing, with increasing retransmission timeouts
  per retry (up to 3x the base pipeline timeout, `MAX_TIMEOUT_ESCALATION_FACTOR`). A message
  is not acknowledged on a timeout, on a JS CPU timeout, or on an HTTP 5xx result; a 4xx
  result is acknowledged. Poison-pill detection (ack and discard after `maxProcessingAttempts`
  failures, default 5) runs whatever `reconnectOnProcessingError` is. The property only decides
  whether a failure also triggers a reconnect; with it off, the connector withholds the ack and waits
  for the broker to retransmit, and the limit still applies.
- Retained-message publish is honored via `retain(context.isRetain())`.

### Wildcard support

`supportsWildcardInTopic(Direction)` reads the `supportsWildcardInTopicInbound`/
`supportsWildcardInTopicOutbound` config properties per direction — both default
`true`, i.e. MQTT-style `+`/`#` wildcards are supported both ways out of the box.

### TLS

`AMQTTClient.initializeMqttSslConfiguration()` builds a HiveMQ `MqttClientSslConfig`
(trust manager factory, hostname verifier, 30s handshake timeout) when
`trustCustomCertificate=true`. Without a custom cert, `mqtts`/`wss` connections fall
back to the system default trust store (`builder.sslWithDefaultConfig()`).

### Sparkplug B Host support

Opt-in via `isSparkplugHost`/`sparkplugHostId`. On connect,
[`SparkplugCertificateManager`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/mqtt/SparkplugCertificateManager.java)
subscribes to `spBv1.0/#` and publishes a Birth Certificate to `spBv1.0/STATE/<hostId>`
(retained, QoS 1); on disconnect it publishes a Death Certificate first. Despite the
name, this is unrelated to X.509/TLS certificates — "Birth/Death Certificate" is
Sparkplug B protocol terminology for online/offline state messages.
(The subscribe pattern was `spBv1.0/+` — a single-level wildcard that never matches any
real Sparkplug topic, since those all have 4+ segments — until fixed 2026-09-22; if you
see `+` referenced anywhere else, it's stale.)

The built-in Sparkplug B payload codec (protobuf encode/decode) is handled by
[`SparkPlugBDeserializer`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/inbound/deserializer/SparkPlugBDeserializer.java)
/
[`SparkPlugBSerializer`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/outbound/serializer/SparkPlugBSerializer.java)
in `dynamic-mapper-service` core (`MappingType.SPARKPLUGB`, forced `SMART_FUNCTION`
transformation), not by this connector package — this Host-mode subscribe/publish
behavior and the mapping-level codec are two independent things that happen to both live
under the "Sparkplug B" feature name. A separate, optional hand-rolled path also exists
for users who want Java instead of JS: `ANY_PAYLOAD` + `EXTENSION_JAVA` with a custom
extension that decodes the protobuf itself (reference implementation:
`dynamic-mapper-extension/.../ProcessorExtensionSparkplugBMeasurement.java`, DDATA-only,
see [extensions.md](../extensions.md)) — that one genuinely does live in the extension
module, but it's an alternative to `MappingType.SPARKPLUGB`, not what backs it. See
[`dynamic-mapper-ui/public/docs/sparkplugb.md`](../../dynamic-mapper-ui/public/docs/sparkplugb.md)
for the mapping-level (NBIRTH/NDATA/DBIRTH/DDATA/NCMD/DCMD) side.

### Gotchas

- `clientId` is passed into `MQTT3Callback` but never actually used by MQTT3 (kept only
  for API symmetry with MQTT5, per commit `7dec71a1b` — "don't set clientId in MQTT3 as
  this is misleading"); MQTT5 extracts a real publisher client ID from user properties.
- Retry/reconnect-on-timeout logic (poison-pill cap, exponential backoff, per-attempt
  timeout scaling, now consolidated in `AbstractMqttCallback`) went through several
  iterations historically (commits `b3e4f1aaa`, `08f313a06`, `776dac7ef`, `c43bc3d22`) —
  if debugging redelivery/timeout behavior, read the current `AbstractMqttCallback`
  rather than assuming any older described behavior still applies.
- `reconnectOnProcessingError` defaults to `false` (changed from an earlier default via
  commit `0278edf41`).

### Testing

The project's integration test suite (`resources/testing/integration/run-tests.sh`) can run
against either a generic public MQTT broker or Cumulocity's MQTT Service, selected via
the `CONNECTOR` argument (`g` = generic MQTT, the default) or the `DM_BROKER_MODE`
environment variable. See [connector-mqtt-service.md](connector-mqtt-service.md) for the
`m` (Cumulocity MQTT Service) mode.
