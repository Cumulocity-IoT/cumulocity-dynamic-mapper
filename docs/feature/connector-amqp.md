# Connector: AMQP

Dynamic Mapper supports two independent, unrelated AMQP protocol versions as separate
connector types. Both extend `AConnectorClient` — see
[connector-framework.md](connector-framework.md) for the shared abstraction.

## AMQP 0-9-1 (`AMQPClient`, `ConnectorType.AMQP_091`)

Implemented by
[`AMQPClient`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/amqp/AMQPClient.java)
using the **RabbitMQ Java client** (`com.rabbitmq.client.*`) — exchange/queue/routing-key
model.

### Configuration

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
| `useSelfSignedCertificate` + cert fields | boolean / string / large text | no | `false` | shown when `protocol=amqps://` |
| `automaticRecovery` | boolean | no | `true` | |
| `supportsWildcardInTopicInbound` / `Outbound` | boolean | no | `true` / `false` | |

### Topic/exchange/queue model

Mapping topic strings map to AMQP routing keys by replacing `/` with `.` (both ways).
If an exchange is configured, it's declared durable via `exchangeDeclare(...)`; a
durable queue is declared via `queueDeclare(...)` (respecting `autoDeleteQueue`) and
bound to the exchange with the routing key. If no exchange is configured, the consumer
reads directly from the named queue.

### Subscribe / publish

`subscribe()` declares exchange/queue/binding then calls `basicConsume()` with
`autoAck = (qos == AT_MOST_ONCE)`. `publishMEAO()` builds `AMQP.BasicProperties` with
`deliveryMode(2)` (persistent) for `AT_LEAST_ONCE`, `deliveryMode(1)` otherwise, and
`contentType=application/json`, then `basicPublish(exchange, routingKey, props, bytes)`.

### QoS / ack

`supportedQOS = [AT_MOST_ONCE, AT_LEAST_ONCE]`. Auto-ack if `AT_MOST_ONCE`; otherwise
manual `basicAck` after successful processing, and `basicNack(deliveryTag, false, true)`
(always requeues) on exception.

### TLS / auth

`amqps://` triggers `factory.useSslProtocol(sslContext)` built from the configured
cert, or default SSL otherwise. No SASL mechanism selection — the RabbitMQ client uses
`PLAIN` implicitly via username/password.

### Reconnection

`connectorSpecificHousekeeping()` only logs a warning on a closed connection; actual
reconnection happens in `monitorSubscriptions()`, which submits a delayed (5s)
`connect()` on the virtual-thread pool — the same pattern used by the MQTT and Pulsar
connectors.

### Gotchas

- **Nack always requeues** (`basicNack(..., requeue=true)`) with no backoff or attempt
  limit — a permanently-failing message can loop forever, unlike Pulsar's poison-pill
  cutoff.
- **Ack is skipped on redelivery**: the callback only calls `basicAck` when
  `envelope.isRedeliver()` is `false` — meaning a redelivered message that succeeds on
  retry is never acknowledged, which likely causes it to be redelivered again
  indefinitely. Worth flagging to anyone debugging repeated AMQP 0-9-1 redelivery.

## AMQP 1.0 (`AMQP10Client`, `ConnectorType.AMQP_10`)

Implemented by
[`AMQP10Client`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/amqp/AMQP10Client.java)
using **Apache Qpid JMS** via the JMS 2.0 API (`org.apache.qpid.jms.JmsConnectionFactory`,
`jakarta.jms.*`) — a completely different client library and message model from the
0-9-1 connector above (no exchanges/routing keys; JMS `Queue`/`Topic` destinations).

### Configuration

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
| `useSelfSignedCertificate` + cert fields | boolean / string / large text | no | `false` | shown when `protocol=amqps://` |
| `automaticRecovery` | boolean | no | `true` | see failover transport, below |
| `supportsWildcardInTopicInbound` / `Outbound` | boolean | no | `false` / `false` | no wildcard support either direction |

### Session / delivery model

A single JMS `Session` per connection, `Session.CLIENT_ACKNOWLEDGE` mode. `subscribe()`
creates a `MessageConsumer` with a `MessageListener`; `publishMEAO()` looks up or
creates a cached `MessageProducer` per resolved destination, builds a `TextMessage`
(optionally setting `JMS_AMQP_ContentType` from the `contentType` property), sets
`deliveryMode` `PERSISTENT` for `AT_LEAST_ONCE` / `NON_PERSISTENT` otherwise, and calls
`producer.send(message)`. The whole producer-get/create/send sequence is
`synchronized(this)`, sharing the lock with `disconnect()`.

### TLS / reconnection

Connection URI is built as `protocol://host:port`; for `amqps://` it appends
`transport.trustAll=true&transport.verifyHost=false` only if a self-signed cert is
configured, else relies on the JVM default trust store. If `automaticRecovery=true`,
the URI is wrapped in Qpid's own `failover:(...)` transport with unlimited reconnect
attempts and exponential backoff — this runs **independently of** the connector
framework's own `monitorSubscriptions()`-driven reconnect (same 5s-delayed
`submitConnect()` pattern as AMQP 0-9-1), which is a documented double-reconnect/race
risk the code explicitly guards against in a few places.

### Gotchas

- **No visible manual acknowledgement**: despite the session being opened in
  `CLIENT_ACKNOWLEDGE` mode, the message listener callback never calls
  `message.acknowledge()`. This looks like a genuine gap — messages may not be properly
  acknowledged at the JMS level, relying instead on provider/session defaults. Worth
  verifying against a live broker before relying on AMQP 1.0 delivery guarantees.
- `sanitizeUri()` is a no-op stub (comment claims the password never appears in the
  URI) — leftover/defensive code with a misleading name; it does not actually redact
  anything.
- Two independent reconnection mechanisms exist (Qpid's `failover:` transport and the
  framework's own housekeeping-driven reconnect) — if you see duplicate reconnect
  attempts, this overlap is why.

## Supported directions (both variants)

Both AMQP 0-9-1 and AMQP 1.0 support `INBOUND` and `OUTBOUND`.
