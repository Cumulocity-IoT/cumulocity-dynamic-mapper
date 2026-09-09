# Connector: Cumulocity MQTT Service

This connector lets Dynamic Mapper talk to Cumulocity's own managed MQTT Service
offering without the user having to supply broker connection details — everything is
auto-wired from the microservice's own tenant credentials. It is **not** a
configuration variant of the generic [MQTT connector](connector-mqtt.md); it is an
entirely separate implementation that speaks the Apache Pulsar wire protocol, not raw
MQTT over TCP/WebSocket, against Cumulocity's internal Pulsar-backed MQTT Service
broker.

## `CUMULOCITY_MQTT_SERVICE` vs `CUMULOCITY_MQTT_SERVICE_PULSAR`

`ConnectorType` (see
[`ConnectorType.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/core/client/ConnectorType.java))
still declares both values, but only one is live:

- **`CUMULOCITY_MQTT_SERVICE`** — dead/removed. In
  [`ConnectorClientFactory.createConnectorClient()`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/core/registry/ConnectorClientFactory.java#L95-L98),
  this case logs a warning ("Connector type CUMULOCITY_MQTT_SERVICE ... is no longer
  supported ... Use CUMULOCITY_MQTT_SERVICE_PULSAR instead") and instantiates no client.
  Kept only so old persisted connector configurations still deserialize. Git history
  confirms removal: `b244fc260` "remove deprecated MQTTServiceClient".
- **`CUMULOCITY_MQTT_SERVICE_PULSAR`** — the real, supported connector, implemented by
  [`MQTTServicePulsarClient`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/pulsar/MQTTServicePulsarClient.java),
  which extends
  [`PulsarConnectorClient`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/pulsar/PulsarConnectorClient.java)
  (see [connector-pulsar.md](connector-pulsar.md) for the base class). Registered in
  `ConnectorRegistry.registerConnectors()`. `singleton = true` — only one instance of
  this connector type is allowed per tenant, since it represents "the" Cumulocity MQTT
  Service, not a user-defined broker.

If you're reading `connector/mqtt/*.java` looking for this connector's implementation,
you're in the wrong package — it lives in `connector/pulsar/`.

## Topic model — inverted from plain Pulsar/MQTT

Instead of subscribing per mapping topic, `MQTTServicePulsarClient` uses exactly two
fixed persistent Pulsar topics per tenant:

- `towardsPlatformTopic` = `persistent://<tenant>/mqtt/from-device` — inbound, device → platform
- `towardsDeviceTopic` = `persistent://<tenant>/mqtt/to-device` — outbound, platform → device

`subscribe(topic, qos)`/`unsubscribe(topic)` are no-ops (they only fire subscription
events for UI bookkeeping) — real per-mapping topic filtering happens downstream, via
message properties, not real Pulsar subscriptions:

- A single `platformConsumer` (subscription type `Failover`, so multiple app instances
  don't double-consume) reads all inbound traffic from `from-device`. The real MQTT
  topic and client ID are carried as Pulsar message properties (`PULSAR_PROPERTY_TOPIC`,
  `PULSAR_PROPERTY_CLIENT_ID`), read back out in `MQTTServicePulsarCallback.received()`.
- A single `deviceProducer` publishes all outbound messages to `to-device`, with the
  target MQTT topic set as both a message property and the partition key
  (`sendMessageToDevice()`).
- `supportsWildcardInTopic()` unconditionally returns `true` for both directions, since
  filtering isn't done via Pulsar topic-pattern subscriptions at all.

## Configuration — mostly pre-wired

`createConnectorSpecification()` exposes a connection-property set where nearly
everything is `.readonly(true).hidden(true)` — the create/config body needs only
type/id/name; connection parameters are auto-filled at connect time:

- `configureCumulocityMqttService()` fetches `MicroserviceCredentials` for the tenant
  (`configurationRegistry.getMicroserviceCredential(tenant)`) and builds
  `authenticationParams` as `{"userId":"<tenant>/<username>","password":"<password>"}`
  with `authenticationMethod=basic`.
- `serviceUrl` is set from `configurationRegistry.getMqttServicePulsarUrl()` (default
  `pulsar://cumulocity:6650`), also read-only/hidden.

So authentication uses the microservice's own bootstrap/service-user credentials, not
user-supplied broker credentials.

## QoS

`supportedQOS = [AT_MOST_ONCE, AT_LEAST_ONCE]` — no `EXACTLY_ONCE`. `publishMEAO()` is
overridden to always use `AT_LEAST_ONCE` regardless of the mapping's configured QoS; the
spec description states this explicitly: "The QoS 'exactly once' is reduced to 'at
least once'."

## Connection lifecycle specifics

- `connect()` is fully overridden and retries via `connectWithRetry()`, trying three
  subscription "strategies" (standard, async, basic) as fallbacks for brokers that don't
  support the Pulsar PIP-344 protocol feature — a defensive compatibility hack for
  Cumulocity-Pulsar-broker version differences.
- Sparkplug B Host Birth/Death certificate lifecycle (reused from the MQTT package's
  `SparkplugCertificateManager`) is wired in on connect/disconnect, with periodic
  Birth-certificate re-publishing via a dedicated `ScheduledExecutorService` — needed
  because this transport doesn't support MQTT-style retained messages the way a real
  MQTT broker would.
- Producer/consumer recreation is guarded by a `ReentrantLock` (not `synchronized`,
  since it's held across blocking Pulsar client calls) — explicitly to stop two racing
  callers (e.g. concurrent `publishMEAO()` calls) from each independently recreating a
  disconnected producer and leaking the loser.
- `disconnect()` nulls `platformConsumer`/`deviceProducer` after the superclass
  disconnect specifically to avoid `AlreadyClosedException` on the next reconnect
  attempt — a documented historical bug fix.
- `connectorSpecificHousekeeping()` actively resubscribes `platformConsumer` if it's
  disconnected. This is necessary here (unlike the base Pulsar class) because
  `deviceProducer` self-heals lazily on the next `publishMEAO()` call, but the platform
  consumer has no equivalent lazy-recreate path — without this housekeeping, a dropped
  subscription would silently kill all inbound device→platform traffic.
- `deleteResources()` is a connector-specific public method (not part of the
  `AConnectorClient` contract) used for permanent connector deletion — it unsubscribes
  from the broker-side Pulsar subscription, creating a temporary consumer if needed
  just to call `unsubscribe()`.

## Gotchas

- Negative-ack / retransmission / processing-cancellation correctness for this
  connector specifically has been iterated on repeatedly (commits `57f23caa8` "Properly
  cancel Processing for MQTT Service + Allow retransmitting of negativ acked messages",
  `766b7d049` "Proper type for Negative Ack Delay") — read `MQTTServicePulsarCallback`
  directly rather than assuming older described behavior.
- The three-tier subscription-strategy fallback exists purely to work around
  Cumulocity-Pulsar-broker versions that don't support PIP-344 — if inbound messages
  aren't arriving, check which strategy actually succeeded in the logs.

## Testing

`resources/script/test/run-tests.sh [SUITE] [CONNECTOR]` supports `CONNECTOR=m` to run
the test suite against Cumulocity MQTT Service (`CUMULOCITY_MQTT_SERVICE_PULSAR`, TLS
port `:9883`, X.509 client-cert auth where the client ID equals the cert CN and the
tenant is embedded in the username), selectable also via the `DM_BROKER_MODE=c8y-mqtt-service`
environment variable. The reliability suite includes a dedicated
`test-cumulocity-mqtt-service` case for this connector's lifecycle.
