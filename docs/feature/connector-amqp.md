# Connector: AMQP

Dynamic Mapper supports two independent, unrelated AMQP protocol versions as separate
connector types. Both extend `AConnectorClient` — see
[connector-framework.md](connector-framework.md) for the shared abstraction.

---

## Requirements

**What it is for.** Connecting to AMQP brokers — both the 0.9.1 dialect (RabbitMQ) and AMQP 1.0
(Azure Service Bus, ActiveMQ, Qpid) — in both directions.

- **Both directions**, with a separate connector type per protocol dialect because their
  addressing models differ (exchange + routing key versus address).
- **Delivery is at-most-once or at-least-once.** Anything above at-most-once is published
  persistently and consumed with explicit acknowledgement; exactly-once does not exist in AMQP and
  a mapping asking for it runs at at-least-once — see [reliability.md](reliability.md).
- **A stronger QoS must never produce a weaker guarantee.** Persistence is decided by "above
  at-most-once", not by equality with a single level.
- **TLS must be supported**, including brokers with a private CA.

---

## Implementation

### AMQP 0-9-1 (`AMQPClient`, `ConnectorType.AMQP_091`)

Implemented by
[`AMQPClient`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/amqp/AMQPClient.java)
using the **RabbitMQ Java client** (`com.rabbitmq.client.*`) — exchange/queue/routing-key
model.

#### Configuration

Built via `ConnectorSpecificationBuilder.create("AMQP Connector", ConnectorType.AMQP_091)`:

| Property | Type | Required | Default | Notes |
|---|---|---|---|---|
| `protocol` | option | yes | `amqp://` | `amqp://`/`amqps://` |
| `host` | string | yes | `localhost` | |
| `port` | numeric | yes | `5672` | |
| `virtualHost` | string | no | `/` | |
| `username` / `password` | string / sensitive | no | `guest` / — | |
| `exchange` | string | no | `""` | |
| `exchangeType` | option | no | `topic` | `topic`/`direct`/`fanout`/`headers` |
| `queuePrefix` | string | no | `""` | |
| `autoDeleteQueue` | boolean | no | `false` | |
| `trustCustomCertificate` + cert fields | boolean / string / large text | no | `false` | shown when `protocol=amqps://` |
| `automaticRecovery` | boolean | no | `true` | |
| `supportsWildcardInTopicInbound` / `Outbound` | boolean | no | `true` / `false` | |

Only `password` is sensitive. `automaticRecovery` enables the RabbitMQ client's own recovery
(network recovery interval 5 s).

#### Topic/exchange/queue model

Mapping topic strings map to AMQP routing keys by replacing `/` with `.` (both ways).
If an exchange is configured, it's declared durable via `exchangeDeclare(...)`; a
durable queue named `<queuePrefix>.<topic with / as .>` is declared via `queueDeclare(...)`
(respecting `autoDeleteQueue`) and bound to the exchange with the routing key. Topic wildcards
are passed through unchanged as part of the routing key, so MQTT-style `+` is not translated to
AMQP's `*`. Without an exchange, the connector still declares the
queue (with the generated name above) and consumes from it.

#### Subscribe / publish

`subscribe()` declares exchange/queue/binding then calls `basicConsume()` with
`autoAck = (qos == AT_MOST_ONCE)`. `publishMEAO()` builds `AMQP.BasicProperties` with
`deliveryMode(2)` (persistent) for `AT_LEAST_ONCE`, `deliveryMode(1)` otherwise, and
`contentType=application/json`, then `basicPublish(exchange, routingKey, props, bytes)`.

#### QoS / ack

`supportedQos = [AT_MOST_ONCE, AT_LEAST_ONCE]`. Auto-ack if `AT_MOST_ONCE`; otherwise the
callback waits for the pipeline result (`ProcessingResultHelper.awaitAckable`) and calls
`basicAck` on success or `basicNack(deliveryTag, false, true)` (always requeues) on a
failure, a timeout, a server-side (5xx) error, or an exception. Client errors (4xx) count as
success, as in the MQTT and Pulsar callbacks. The consumer thread blocks while it waits, so
messages on one channel are settled in order.

#### TLS / auth

`amqps://` triggers `factory.useSslProtocol(sslContext)` built from the configured
cert, or default SSL otherwise. No SASL mechanism selection — the RabbitMQ client uses
`PLAIN` implicitly via username/password.

#### Reconnection

`connectorSpecificHousekeeping()` only logs a warning on a closed connection; actual
reconnection happens in `monitorSubscriptions()`, which submits `submitConnect()` on the
virtual-thread pool with a linear backoff (`attempt * 10 s`, capped at 5 minutes), gated by
`nextReconnectTimeMs`, like the MQTT connectors. The RabbitMQ client's `automaticRecovery`
runs in parallel.

#### Gotchas

- **Nack requeues** (`basicNack(..., requeue=true)`) with no backoff, but only until the message failed
  `maxProcessingAttempts` times (default 5); then it is acked and dropped. The message is identified by its
  AMQP `messageId` property, or by routing key and body hash when the publisher set none, because the
  delivery tag changes on every redelivery. **AMQP 1.0 has no such limit:** an unacknowledged message is
  redelivered only when the session restarts. Also, the JMS specification says `acknowledge()` in
  client-acknowledge mode acknowledges every message the session has consumed so far, so a later success may
  acknowledge an earlier failed message. Not verified against the Qpid JMS client here.
- **The callback needs the channel.** `AMQPCallback` receives the channel and the `autoAck` flag
  in its constructor. Earlier it was built with a `null` channel, so it never sent an ack for
  `AT_LEAST_ONCE` subscriptions, and it skipped the ack for redelivered messages. Both are fixed;
  no ack or nack is sent for `autoAck=true` consumers.

### AMQP 1.0 (`AMQP10Client`, `ConnectorType.AMQP_10`)

Implemented by
[`AMQP10Client`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/amqp/AMQP10Client.java)
using **Apache Qpid JMS** via the JMS 2.0 API (`org.apache.qpid.jms.JmsConnectionFactory`,
`jakarta.jms.*`) — a completely different client library and message model from the
0-9-1 connector above (no exchanges/routing keys; JMS `Queue`/`Topic` destinations).

#### Configuration

Built via `ConnectorSpecificationBuilder.create("AMQP 1.0 Connector", ConnectorType.AMQP_10)`:

| Property | Type | Required | Default | Notes |
|---|---|---|---|---|
| `protocol` | option | yes | `amqp://` | `amqp://`/`amqps://` |
| `host` | string | yes | `localhost` | |
| `port` | numeric | yes | `5672` | |
| `username` / `password` | string / sensitive | no | — | |
| `clientId` | string | no | — | JMS client/container ID |
| `destinationType` | option | yes | `queue` | `queue`/`topic` |
| `contentType` | string | no | — | e.g. `application/json;charset=utf-8` |
| `trustCustomCertificate` + cert fields | boolean / string / large text | no | `false` | shown when `protocol=amqps://` |
| `automaticRecovery` | boolean | no | `true` | see failover transport, below |
| `supportsWildcardInTopicInbound` / `Outbound` | boolean | no | `false` / `false` | no wildcard support either direction |

Only `password` is sensitive.

#### Session / delivery model

A single JMS `Session` per connection, `Session.CLIENT_ACKNOWLEDGE` mode. `subscribe()`
creates a `MessageConsumer` with an `AMQP10Callback` listener (`AT_MOST_ONCE` acknowledges
before dispatch, higher levels acknowledge once the pipeline result reports success; unsupported message
types are acknowledged and dropped); `publishMEAO()` looks up or
creates a cached `MessageProducer` per resolved destination, builds a `TextMessage`
(optionally setting `JMS_AMQP_ContentType` from the `contentType` property), sets
`deliveryMode` `PERSISTENT` for `AT_LEAST_ONCE` / `NON_PERSISTENT` otherwise, and calls
`producer.send(message)`. The whole producer-get/create/send sequence is
`synchronized(this)`, sharing the lock with `disconnect()`.

#### TLS / reconnection

Connection URI is built as `protocol://host:port`; for `amqps://` it appends
`transport.trustAll=true&transport.verifyHost=false` only if a self-signed cert is
configured, else relies on the JVM default trust store. If `automaticRecovery=true`,
the URI is wrapped in Qpid's own `failover:(...)` transport with unlimited reconnect
attempts and exponential backoff (initial 1 s, base 5 s, max 30 s) — this runs
**independently of** the connector framework's own `monitorSubscriptions()`-driven
reconnect (same linear 10 s step backoff, capped at 5 minutes, as AMQP 0-9-1), which is a
double-reconnect/race risk the code explicitly guards against in a few places.

**Security note:** with `amqps://` and `trustCustomCertificate=true` the URI gets
`transport.trustAll=true&transport.verifyHost=false`. The configured certificate is not used
and server verification is disabled entirely.

#### Gotchas

- **Acknowledgement waits for the pipeline**: `AMQP10Callback` calls `message.acknowledge()`
  only after `awaitAckable` reports success. A failed or timed-out mapping leaves the message
  unacknowledged, but the callback never calls `session.recover()`, so redelivery happens only
  on the next session or consumer restart.
- `sanitizeUri()` is a no-op stub (comment claims the password never appears in the
  URI) — leftover/defensive code with a misleading name; it does not actually redact
  anything.
- Two independent reconnection mechanisms exist (Qpid's `failover:` transport and the
  framework's own housekeeping-driven reconnect) — if you see duplicate reconnect
  attempts, this overlap is why.

### Supported directions (both variants)

Both AMQP 0-9-1 and AMQP 1.0 support `INBOUND` and `OUTBOUND`.
