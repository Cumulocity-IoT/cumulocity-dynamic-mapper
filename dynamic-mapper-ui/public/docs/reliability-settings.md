---
title: Reliability settings
---

### Using reliability settings in mappings {#reliability-settings}

You can set a QoS (Quality of Service) level for a mapping in the **QoS** field of the mapping properties. The
level states when the connector may acknowledge a message to its broker: before the mapping runs (QoS 0), or only
after the mapping pipeline succeeded (QoS 1 and 2). A connector that does not support the selected level handles
the mapping at the nearest lower level it supports, and the QoS field shows which connectors do so. The service logs
each such clamp once as a warning. When several mappings match one message, the strongest requested level wins.
The effect of QoS depends on the connector (message source):

- **AMQP 0-9-1**: Supports QoS 0 and 1. QoS 0 uses automatic acknowledgment and non-persistent delivery. QoS 1
  acknowledges after processing, requeues the message on failure, and publishes persistently.
- **AMQP 1.0**: Supports QoS 0 and 1. QoS 1 acknowledges after processing and publishes persistently (JMS delivery
  modes).
- **Apache Pulsar**: Supports QoS 0, 1, and 2. QoS 0 acknowledges immediately. QoS 1 and 2 acknowledge after
  successful processing.
- **Cumulocity MQTT-Service (device isolation)**: Supports QoS 0 and 1. Inbound messages are always acknowledged
  after processing, whatever the mapping says.
- **Google Cloud Pub/Sub**: Outbound always waits for the Pub/Sub publish acknowledgement (message ID) before
  considering a message sent, regardless of the mapping's QoS setting. Inbound, QoS 0 acks the message
  immediately before processing (at-most-once). QoS 1 acks only after successful processing and nacks on
  error to trigger redelivery (at-least-once). QoS 2 is handled as QoS 1.
- **HTTP Endpoint (Default HTTP Connector)**: Inbound only. The connector answers after the pipeline ran, so it
  always works at-least-once. A mapping with QoS 0 is handled at QoS 1. A pipeline failure or timeout returns HTTP
  500, so the sender can retry.
- **Kafka**: Supports QoS 0 and 1. QoS 0 commits the offset immediately. QoS 1 commits the offset only after
  successful processing and reads the record again on failure. QoS 2 is handled as QoS 1.
- **MQTT**: Supports all QoS levels (0-2) with varying acknowledgment behaviors.
- **REST Polling**: Inbound only. The connector acknowledges a poll response after the pipeline ran.
- **Webhook**: Outbound only. It always works at-least-once.

**Understanding QoS Levels:**
- **QoS 0 (At most once):** Fastest, no delivery guarantee. Use for non-critical data where occasional loss is
  acceptable.
- **QoS 1 (At least once):** Guaranteed delivery, but duplicates possible. Recommended for most use cases.
- **QoS 2 (Exactly once):** Highest reliability, slowest. Use for critical data where duplicates must be avoided.
  Inbound MQTT messages are handled at QoS 1 without duplicate detection, so a mapping must still tolerate
  duplicates.

:::caution
**Performance Considerations:** Higher QoS levels (1-2) provide better delivery guarantees but impact
performance. QoS 2 requires a four-way handshake and is significantly slower. Choose the lowest QoS level that
meets your reliability requirements.
:::

Higher QoS levels (1-2) provide better delivery guarantees, but not all connectors support them. Only MQTT and
Apache Pulsar offer all three levels.

| Connector | QoS | Inbound | Outbound |
|---|:---:|---|---|
| **AMQP 0-9-1** (RabbitMQ, etc.) | 0 | Automatic acknowledgment, before processing | Non-persistent delivery |
| **AMQP 0-9-1** (RabbitMQ, etc.) | 1 | Acknowledged after processing, requeued on failure | Persistent delivery |
| **AMQP 0-9-1** (RabbitMQ, etc.) | 2 | Handled as QoS 1 | Handled as QoS 1 |
| **AMQP 1.0** (Azure Service Bus, Artemis, Solace, etc.) | 0 | Acknowledged before processing | `NON_PERSISTENT` delivery mode |
| **AMQP 1.0** (Azure Service Bus, Artemis, Solace, etc.) | 1 | `CLIENT_ACKNOWLEDGE` after processing | `PERSISTENT` delivery mode |
| **AMQP 1.0** (Azure Service Bus, Artemis, Solace, etc.) | 2 | Handled as QoS 1 | Handled as QoS 1 |
| **Apache Pulsar** | 0 | Acknowledged immediately | Asynchronous send, no acknowledgment |
| **Apache Pulsar** | 1 | Acknowledged after successful processing, negative acknowledgment on failure | Blocking send |
| **Apache Pulsar** | 2 | As QoS 1, plus broker-side duplicate suppression | Blocking send |
| **Cumulocity MQTT-Service (device isolation)** | 0 | Not used. Messages are always acknowledged after processing. | Asynchronous send to the MQTT Service, failures are only logged |
| **Cumulocity MQTT-Service (device isolation)** | 1 | Not used. Messages are always acknowledged after processing. | The send waits for the MQTT Service to confirm |
| **Cumulocity MQTT-Service (device isolation)** | 2 | Handled as QoS 1 | Handled as QoS 1 |
| **Google Cloud Pub/Sub** | 0 | Ack sent immediately, before processing (at-most-once) | QoS setting has no effect. Publish always waits for the Pub/Sub publish acknowledgement (message ID). |
| **Google Cloud Pub/Sub** | 1 | Ack sent only after successful processing, nack (redelivery) on error | QoS setting has no effect. Publish always waits for the Pub/Sub publish acknowledgement (message ID). |
| **Google Cloud Pub/Sub** | 2 | Handled as QoS 1 | QoS setting has no effect. Publish always waits for the Pub/Sub publish acknowledgement (message ID). |
| **HTTP Endpoint** | any | Response sent after the pipeline ran: `200` on success, `500` on pipeline failure or timeout. QoS 0 is handled as QoS 1. | not applicable |
| **Kafka** | 0 | Offset committed immediately | Waits for the broker acknowledgment of all in-sync replicas (`acks=all` default, overridable per connector) |
| **Kafka** | 1 | Offset committed after successful processing, record read again on failure | Same as QoS 0 |
| **Kafka** | 2 | Handled as QoS 1 | Handled as QoS 1 |
| **MQTT** | 0 | ACKs are sent to broker directly after message received | Received as QoS 1 at Notification 2.0 but published as QoS 0 |
| **MQTT** | 1 | ACKs are sent only when the message is successfully processed at C8Y | ACKs are sent only to Notification 2.0 when the message is successfully published to broker, published as QoS 1 |
| **MQTT** | 2 | Currently handled as QoS 1, no logic to check for de-duplication | Handled as QoS 1, published as QoS 2 |
| **REST Polling** | any | Poll response acknowledged after the pipeline ran | not applicable |
| **Webhook** | any | not applicable | Always at-least-once: the Notification 2.0 message is acknowledged only when the HTTP request succeeded |

#### Acknowledgment and redelivery

With QoS 1 or 2, a failed message stays unacknowledged, and the broker delivers it again. A client-side error (HTTP
status below 500) counts as handled and is acknowledged, because a redelivery would fail again. Redelivery
repeats the whole mapping, so a mapping must tolerate duplicates. To prevent one faulty message from blocking a
subscription, connectors that retry (MQTT, Pulsar, Cumulocity MQTT Service, Kafka, AMQP, and Google Pub/Sub) drop a message
after it failed the number of times set in **Maximum processing attempts per message** (Service configuration,
Processing tab, default 3). The error stays in the mapping status. A slow mapping delays acknowledgments and
therefore the whole subscription. Use QoS 0 for high-volume data that tolerates loss.

QoS covers the leg between the broker and the Dynamic Mapper only. For example, over Cumulocity MQTT-Service a
message sent to a disconnected device is dropped whatever the QoS, because the service requires clean sessions.

#### Processing timeouts

Two tenant-wide limits protect the service from a Smart Function that runs too long. You set them under
**Configuration → Service configuration → Processing**. See
[Service configuration](/c8y-pkg-dynamic-mapper/introduction/service-configuration).

- **Maximum CPU time for Smart Function JavaScript code** (default 5000 ms) stops the JavaScript.
- **Maximum end-to-end processing time for Smart Functions** (default 8000 ms) covers the JavaScript and the
  Cumulocity API calls that follow. It must be greater than the CPU time.

Messages of other mapping types are not limited by these two settings, but the service still cancels a message that
runs longer than 120 seconds. When the end-to-end time expires, the message is not acknowledged and the broker
redelivers it, and the retry gets more time. The HTTP Endpoint answers `500`.

#### Deactivating a failing mapping

The **Max failure count** field in the mapping properties sets how many failures in a row deactivate the mapping
automatically. The default 0 turns the check off.

- The count restarts at 0 after the first message that succeeds, and when you activate the mapping again.
- A message dropped by a filter is not a failure. A test run does not count.
- A failure is an error in any processing step: reading the payload, resolving the device, a JSONata or Smart
  Function error, an error from a Java extension, or a failed request to Cumulocity. A Smart
  Function that the end-to-end time limit cancels counts as a failure. An empty result from a Java extension is
  ignored and is not a failure.
- One message that fails in two steps adds 2 to the count.
- When the limit is reached, the service writes a `d11r_mappingFailureEvent` (see **Monitoring → Service events**),
  sets the mapping inactive, and releases its topic subscription. The broker keeps sending, and the mapping no
  longer receives anything, so check the mapping status when data stops.
- A mapping that alternates between success and failure never reaches the limit. Use the **Errors** and **Status**
  columns under **Monitoring → Statistic processed** to find such mappings.
