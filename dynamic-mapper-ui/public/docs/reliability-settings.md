---
title: Reliability settings
---

### Using reliability settings in mappings {#reliability-settings}

You can set a QoS (Quality of Service) level for a mapping. The table below shows the effect of QoS when
processing mappings, depending on the connector (message source):

- **AMQP 0-9-1**: Supports QoS 0 and 1 (non-persistent and persistent delivery).
- **AMQP 1.0**: Supports QoS 0 and 1 (non-persistent and persistent delivery via JMS delivery modes).
- **Apache Pulsar**: Supports QoS 0 equivalent (fire-and-forget).
- **Cumulocity MQTT Service (device isolation)**: Supports QoS 0 and 1.
- **Google Cloud Pub/Sub**: Outbound always waits for the Pub/Sub publish acknowledgement (message ID) before
  considering a message sent, regardless of the mapping's QoS setting. Inbound, QoS 0 acks the message
  immediately before processing (at-most-once); QoS 1 and 2 ack only after successful processing and nack on
  error to trigger redelivery (at-least-once, no de-duplication for QoS 2).
- **HTTP Connector**: Only supports QoS 0 (immediate response, no guarantees).
- **Kafka**: Limited to QoS 0 for outbound operations.
- **MQTT**: Supports all QoS levels (0-2) with varying acknowledgment behaviors.
- **Webhook**: Primarily outbound, with conditional acknowledgments based on QoS.

**Understanding QoS Levels:**
- **QoS 0 (At most once):** Fastest, no delivery guarantee. Use for non-critical data where occasional loss is
  acceptable.
- **QoS 1 (At least once):** Guaranteed delivery, but duplicates possible. Recommended for most use cases.
- **QoS 2 (Exactly once):** Highest reliability, slowest. Use for critical data where duplicates must be avoided.

:::caution
**Performance Considerations:** Higher QoS levels (1-2) provide better delivery guarantees but impact
performance. QoS 2 requires a four-way handshake and is significantly slower. Choose the lowest QoS level that
meets your reliability requirements.
:::

Higher QoS levels (1-2) provide better delivery guarantees, but are not supported by all connectors. MQTT offers
the most complete QoS support.

| Connector | QoS | Inbound | Outbound |
|---|:---:|---|---|
| **AMQP 0-9-1** (RabbitMQ, etc.) | 0 | not relevant | Non-persistent delivery, no acknowledgment guarantee |
| **AMQP 0-9-1** (RabbitMQ, etc.) | 1 | not relevant | Persistent delivery with acknowledgment after processing |
| **AMQP 0-9-1** (RabbitMQ, etc.) | 2 | not relevant | not supported |
| **AMQP 1.0** (Azure Service Bus, Artemis, Solace, etc.) | 0 | not relevant | NON_PERSISTENT delivery mode, no acknowledgment guarantee |
| **AMQP 1.0** (Azure Service Bus, Artemis, Solace, etc.) | 1 | not relevant | PERSISTENT delivery mode with CLIENT_ACKNOWLEDGE after processing |
| **AMQP 1.0** (Azure Service Bus, Artemis, Solace, etc.) | 2 | not relevant | not supported |
| **Apache Pulsar** | 0 | not relevant | No acknowledgment (similar to MQTT QoS 0) |
| **Apache Pulsar** | 1 | not relevant | not supported |
| **Apache Pulsar** | 2 | not relevant | not supported |
| **Cumulocity MQTT Service (device isolation)** | 0 | not relevant | Received as QoS 1, sending ack directly after receiving message |
| **Cumulocity MQTT Service (device isolation)** | 1 | not relevant | Received as QoS 1, sending ack only when request was successful |
| **Cumulocity MQTT Service (device isolation)** | 2 | not relevant | not supported |
| **Google Cloud Pub/Sub** | 0 | Ack sent immediately, before processing (at-most-once) | QoS setting has no effect — publish always waits for the Pub/Sub publish acknowledgement (message ID) |
| **Google Cloud Pub/Sub** | 1 | Ack sent only after successful processing; nack (redelivery) on error | QoS setting has no effect — publish always waits for the Pub/Sub publish acknowledgement (message ID) |
| **Google Cloud Pub/Sub** | 2 | Handled as QoS 1, no logic to check for de-duplication | QoS setting has no effect — publish always waits for the Pub/Sub publish acknowledgement (message ID) |
| **Http Connector** | 0 | Process asynchronous, send response directly after receiving message | not relevant |
| **Http Connector** | 1 | not supported | not relevant |
| **Http Connector** | 2 | not supported | not relevant |
| **Kafka** | 0 | not relevant | No acknowledgment (similar to MQTT QoS 0) |
| **Kafka** | 1 | not relevant | not supported |
| **Kafka** | 2 | not relevant | not supported |
| **MQTT** | 0 | ACKs are sent to broker directly after message received | Received as QoS 1 at Notification 2.0 but published as QoS 0 |
| **MQTT** | 1 | ACKs are sent only when the message is successfully processed at C8Y | ACKs are sent only to Notification 2.0 when the message is successfully published to broker, published as QoS 1 |
| **MQTT** | 2 | Currently handled as QoS 1, no logic to check for de-duplication | Handled as QoS 1, published as QoS 2 |
| **Webhook** | 0 | not relevant | Received as QoS 1, sending ack directly after receiving message |
| **Webhook** | 1 | not relevant | Received as QoS 1, sending ack only when request was successful |
| **Webhook** | 2 | not relevant | Received as QoS 1, sending ack only when request was successful |

#### "not relevant" in the table above

**"not relevant"** in the Inbound column means the QoS setting on the mapping has *no effect* on inbound behavior
for that connector. The connector uses its own fixed protocol-level acknowledgment mechanism regardless of the
mapping QoS value:

- **AMQP 0-9-1 (inbound)** — messages are acknowledged (`basic.ack`) after processing; the QoS field is not
  applicable.
- **AMQP 1.0 (inbound)** — messages are acknowledged via JMS `CLIENT_ACKNOWLEDGE` after processing; the QoS field
  is not applicable.
- **Apache Pulsar (inbound)** — messages are acknowledged by the Pulsar consumer after successful processing; the
  QoS field is not applicable.
- **Cumulocity MQTT Service (inbound)** — acknowledgment is governed by the Notification 2.0 subscription
  protocol; the mapping QoS is not used for inbound direction.
- **Kafka (inbound)** — consumer offsets are committed after processing; the QoS field does not change this
  behavior.
- **Webhook (inbound)** — Webhook is an outbound-only connector; inbound is not applicable.

