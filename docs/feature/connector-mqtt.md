# Connector: MQTT

The generic MQTT connector lets Dynamic Mapper connect to any external MQTT 3.1.1 or
5.0 broker (public or self-hosted) for both inbound (device → Cumulocity) and outbound
(Cumulocity → device) mappings. For the connector-agnostic framework this builds on
(`AConnectorClient`, `ConnectorSpecification`, message dispatch), see
[connector-framework.md](connector-framework.md). For Cumulocity's own managed MQTT
Service offering, see [connector-mqtt-service.md](connector-mqtt-service.md) — despite
the similar name it is a different code path, not a configuration variant of this
connector.

## Class hierarchy and library

[`AMQTTClient`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/mqtt/AMQTTClient.java)
(abstract, ~700 lines) extends `AConnectorClient` and implements everything
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

## Configuration (`ConnectorSpecification`)

Built in
[`AMQTTClient.buildCommonMqttProperties()`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/mqtt/AMQTTClient.java#L517-L633),
shared by both version-specific clients:

| Property | Type | Required | Default | Notes |
|---|---|---|---|---|
| `version` | option | yes | `3.1.1` / `5.0` | fixed per client class |
| `protocol` | option | yes | `mqtt://` | `mqtt://`, `mqtts://`, `ws://`, `wss://` |
| `mqttHost` | string | yes | — | |
| `mqttPort` | numeric | yes | — | |
| `user` / `password` | string / sensitive | no | — | |
| `clientId` | string | yes | — | |
| `useSelfSignedCertificate` | boolean | no | `false` | shown only for `mqtts`/`wss` |
| `nameCertificate`, `fingerprintSelfSignedCertificate`, `certificateChainInPemFormat` | string / large text | no | — | shown only when `useSelfSignedCertificate=true` |
| `disableHostnameValidation` | boolean | no | `false` | same condition |
| `supportsWildcardInTopicInbound` | boolean | no | `true` | |
| `supportsWildcardInTopicOutbound` | boolean | no | `true` | changed from a `false` default in an earlier version (commit `523277c6c`) |
| `serverPath` | string | no | — | shown only for `ws`/`wss` |
| `cleanSession` | boolean | no | `true` | MQTT3 `.cleanSession()`, MQTT5 `.cleanStart()` |
| `reconnectOnProcessingError` | boolean | no | `false` | shown only when `cleanSession=false`; controls QoS 1+ retransmission behavior on error |
| `isSparkplugHost` | boolean | no | `false` | enables Sparkplug B Host mode |
| `sparkplugHostId` | string | no | — | shown only when `isSparkplugHost=true` |

Spec name/type: `MQTT3Client` registers as `ConnectorSpecificationBuilder.create("MQTT", ConnectorType.MQTT)`;
`MQTT5Client` as `create("MQTT 5.0", ConnectorType.MQTT)` — both share the same
`ConnectorType.MQTT`. `singleton = false` — multiple MQTT connector instances per
tenant are allowed.

## Connection lifecycle

- `connect()`/`disconnect()` are orchestrated in `AMQTTClient` (lines 225-300, 353-412)
  and delegate the actual HiveMQ client build/connect/disconnect calls to the
  version-specific subclass.
- Reconnect scheduling lives entirely in `connectorSpecificHousekeeping()`
  (`MQTT3Client.java:461-473`, `MQTT5Client.java:486-498`, identical logic): if the
  client is disconnected and should be connected, it computes an exponential backoff
  (`RECONNECT_DELAY_STEP_MS=10000`, capped at `RECONNECT_DELAY_MAX_MS=300000` — 5
  minutes) and resubmits `connect()` on the virtual-thread pool. Disconnect listeners
  only record the drop and set the next retry time; they do not themselves trigger
  reconnection — housekeeping owns all reconnect scheduling (explicit code comment).
- `AtomicInteger reconnectAttempt`, `AtomicLong nextReconnectTimeMs`/`connectGeneration`
  guard against races from a prior (superseded) connection's callbacks firing after a
  new connect attempt has started — the result of historical lifecycle/concurrency bug
  fixes (commits `dc098d56e`, `ff1622522`, `2815e8bd1`).

## QoS and delivery

- `Qos` enum (`AT_MOST_ONCE`, `AT_LEAST_ONCE`, `EXACTLY_ONCE`) maps directly to MQTT QoS
  0/1/2 via `MqttQos.fromCode(qos.ordinal())`.
- `AMQTTClient` supports all three QoS levels; `adjustQos()` downgrades a requested QoS
  to the highest one the connector actually supports.
- Effective QoS for inbound delivery is `min(publish QoS, mapping QoS)`
  (`AbstractMqttCallback.java:185`).
- QoS 0 messages ack immediately with no reliability guarantee; QoS 1/2 messages are
  acked manually after successful processing, with increasing retransmission timeouts
  per retry and poison-pill detection after `MAX_CONSECUTIVE_RECONNECTS=5`.
- Retained-message publish is honored via `retain(context.isRetain())`.

## Wildcard support

`supportsWildcardInTopic(Direction)` reads the `supportsWildcardInTopicInbound`/
`supportsWildcardInTopicOutbound` config properties per direction — both default
`true`, i.e. MQTT-style `+`/`#` wildcards are supported both ways out of the box.

## TLS

`AMQTTClient.initializeMqttSslConfiguration()` builds a HiveMQ `MqttClientSslConfig`
(trust manager factory, hostname verifier, 30s handshake timeout) when
`useSelfSignedCertificate=true`. Without a custom cert, `mqtts`/`wss` connections fall
back to the system default trust store (`builder.sslWithDefaultConfig()`).

## Sparkplug B Host support

Opt-in via `isSparkplugHost`/`sparkplugHostId`. On connect,
[`SparkplugCertificateManager`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/mqtt/SparkplugCertificateManager.java)
subscribes to `spBv1.0/+` and publishes a Birth Certificate to `spBv1.0/STATE/<hostId>`
(retained, QoS 1); on disconnect it publishes a Death Certificate first. Despite the
name, this is unrelated to X.509/TLS certificates — "Birth/Death Certificate" is
Sparkplug B protocol terminology for online/offline state messages. Actual Sparkplug B
payload encode/decode (protobuf) is handled by a separate extension, not by this
connector package.

## Gotchas

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

## Testing

The project's integration test suite (`resources/script/test/run-tests.sh`) can run
against either a generic public MQTT broker or Cumulocity's MQTT Service, selected via
the `CONNECTOR` argument (`g` = generic MQTT, the default) or the `DM_BROKER_MODE`
environment variable. See [connector-mqtt-service.md](connector-mqtt-service.md) for the
`m` (Cumulocity MQTT Service) mode.
