# Connector: Kafka

The Kafka connector lets Dynamic Mapper produce to and consume from Apache Kafka
topics, for both inbound and outbound mappings. Implemented by
[`KafkaClientV2`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/kafka/KafkaClientV2.java),
which extends `AConnectorClient` — see [connector-framework.md](connector-framework.md)
for the shared abstraction it builds on.

---

## Requirements

**What it is for.** Consuming from and producing to Kafka topics, for tenants whose integration
layer is Kafka rather than a device broker.

- **Both directions.**
- **MQTT-style wildcards in a mapping topic are translated to a Kafka topic pattern**, so a single
  mapping can cover a family of topics even though Kafka itself has no wildcard subscriptions.
- **Delivery is at-most-once or at-least-once.** At-least-once is realised by deferring the offset
  commit until the pipeline reports success, so a failure re-delivers from the uncommitted offset.
  Exactly-once would require transactional consume-process-produce and is deliberately not
  offered — see [reliability.md](reliability.md).
- **Authentication covers plaintext and the common SASL mechanisms**, with TLS and a custom CA.
- **Consumer-group identity must be stable for mappings and isolated for ad-hoc inspection.**
  Mappings share the connector's group so offsets are remembered; a Message Explorer session must
  never consume from that group, or it would steal messages from the mappings and move their
  offsets.

---

## Implementation

### Library

Apache Kafka's official Java client (`org.apache.kafka.clients.*`), not Spring Kafka.
Key/value are always `String` (`KafkaProducer<String,String>`,
`KafkaConsumer<String,String>`) — there is no support for `byte[]`/Avro/Schema
Registry; payloads are always treated as UTF-8 strings. An `AdminClient` is also used,
purely for connectivity testing (`listTopics()`).

### Configuration (`ConnectorSpecification`)

Built in `createConnectorSpecification()`
(`ConnectorSpecificationBuilder.create("Kafka", ConnectorType.KAFKA)`):

| Property | Type | Required | Default | Notes |
|---|---|---|---|---|
| `bootstrapServers` | string | yes | — | |
| `useSelfSignedCertificate` | boolean | no | `false` | trust a self-signed/internal CA |
| `nameCertificate`, `fingerprintSelfSignedCertificate`, `certificateChainInPemFormat` | string / large text | no | — | shown when `useSelfSignedCertificate=true` |
| `disableHostnameValidation` | boolean | no | `false` | insecure, dev/test only |
| `username` | string | no | — | e.g. a Confluent Cloud API key |
| `password` | sensitive | no | — | shown when `username` set |
| `saslMechanism` | option | no | `SCRAM-SHA-256` | `SCRAM-SHA-256`/`SCRAM-SHA-512`/`PLAIN`, shown when `username` set |
| `groupId` | string | yes | auto-generated if absent | see below |
| `defaultPropertiesProducer` / `defaultPropertiesConsumer` | map | no | `{}` | free-form producer/consumer property overrides |
| `propertiesProducer` / `propertiesConsumer` | large text, readonly | — | contents of `kafka-producer.properties`/`kafka-consumer.properties` | display-only |

`groupId` defaults to `"dynamic-mapper-" + connectorIdentifier + additionalSubscriptionIdTest`
if not configured.

### TLS / SASL

`buildKafkaProperties()`: if `username`+`password` are set, uses `SASL_SSL` with a
JAAS config built for the selected mechanism (`PLAIN`→`PlainLoginModule`,
`SCRAM-SHA-256`/`-512`→`ScramLoginModule`, unknown mechanisms fall back to Scram with a
warning). Else if `useSelfSignedCertificate` is set, uses `security.protocol=SSL` only
(no auth) with an inline PEM truststore (`ssl.truststore.type=PEM`,
`ssl.truststore.certificates=<PEM>` — Kafka natively supports inline PEM per KIP-651,
unlike MQTT/AMQP which need a manual KeyStore/SSLContext). Otherwise `PLAINTEXT`.
`disableHostnameValidation` sets `ssl.endpoint.identification.algorithm=""` with an
explicit warning log.

### Connection lifecycle

- `initialize()` builds Kafka properties, creates an `AdminClient`, and probes
  connectivity with `listTopics().names().get(10, SECONDS)`; on failure it explicitly
  closes the AdminClient (`closeAdminClientQuietly()`) to avoid a leaked background
  thread that would otherwise retry ("Rebootstrapping...") forever.
- `connect()` re-tests connectivity via `retryOperation("Kafka connection", 3, 2000, …)`
  and creates the real `KafkaProducer`.
- `disconnect()` signals every per-topic poll loop to stop, waits up to 5s
  (`CONSUMER_SHUTDOWN_TIMEOUT_MS`) per task, then closes the producer (10s timeout) and
  admin client.
- Each subscribed topic gets its **own dedicated `KafkaConsumer`** running in its own
  virtual thread — one consumer per topic, not one shared consumer for all topics — so
  failures on one topic don't affect others.

### Subscribe / unsubscribe

- `subscribe(topic, qos)` creates a new `KafkaConsumer` per call, using the shared
  consumer properties (which carry the connector-wide `group.id`). If the topic
  contains MQTT-style wildcards (`+`/`#`), it subscribes via a translated Java regex
  `Pattern`; otherwise a plain topic-name subscription.
- Offset commit is manual and queued: `handleSuccessfulProcessing()` queues an offset
  via `requestCommit()` if `enable.auto.commit` is disabled, and the actual
  `commitSync()` only ever happens on the owning poll-loop thread, because
  `KafkaConsumer` is not thread-safe.
- `subscribeExplorer()` (used by the Message Explorer/test-connection UI) is a separate
  path: it always uses a fresh, never-reused ephemeral `group.id` and
  `auto.offset.reset=latest`, specifically to avoid resuming from a shared consumer
  group's committed offset or triggering a rebalance against a real mapping consumer.
  Explorer consumers are tracked in separate maps/failure-counters from mapping
  consumers for the same reason.
- `unsubscribe(topic)` calls `consumer.wakeup()` (the one thread-safe `KafkaConsumer`
  call) and waits up to 5s for the poll thread to exit.

### Publish (`publishMEAO`)

Synchronous send with a 10s wait: `kafkaProducer.send(record).get(10, TimeUnit.SECONDS)`
— effectively sync-over-async. The Kafka record key is whatever value the mapping
bound to `_CONTEXT_DATA_.key` (`context.getKey()`), so mappings can control Kafka
partitioning via that binding. Topic is the mapping's own publish topic if set, else
the resolved default. There's no explicit `acks` config in code — that's controlled via
`kafka-producer.properties`/`defaultPropertiesProducer` overrides. One failed request
records an error on that request and on the context but doesn't stop the rest of the
batch.

### Supported directions and QoS

Both `INBOUND` and `OUTBOUND`. Kafka has no native QoS concept — `supportedQOS` is
hardcoded to `AT_MOST_ONCE` — though the manual offset-commit logic above still applies
when `enable.auto.commit=false`.

### Housekeeping

`connectorSpecificHousekeeping()` prunes completed/cancelled tasks from the consumer
task map. A separate `monitorSubscriptions()` retries topics whose poll-failure count
is between 1 and `MAX_RETRY_ATTEMPTS` (3) by unsubscribing and resubscribing.

### Gotchas

- `supportsWildcardInTopic()` returns a flat `false` (comment: "Kafka doesn't support
  wildcards"), which **contradicts the actual implementation** — `subscribe()` and
  `subscribeExplorer()` both translate MQTT-style wildcards into a Kafka regex `Pattern`
  subscription. If wildcard topics silently fail validation in the UI despite working
  at the consumer level, this flag is why.
- `sendSubscriptionEvents` fire from `subscribe()`/`unsubscribe()` but not from
  `subscribeExplorer()`/`unsubscribeExplorer()` — explorer subscriptions produce no
  subscription event.
- Extensive hand-rolled thread-ownership discipline exists around `KafkaConsumer` not
  being thread-safe (commits and closes are always deferred to the owning poll-loop
  thread via `wakeup()`/pending-commit queues) — a result of historical concurrency bug
  fixes (`ff1622522` "fix connector lifecycle and concurrency bugs across all broker
  clients", `d444db106`, `b6c38455d` "close admin clients on failure").
