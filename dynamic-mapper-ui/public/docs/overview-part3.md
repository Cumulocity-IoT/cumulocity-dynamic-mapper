Each template includes sample code demonstrating best practices for:

- Accessing and transforming payload data
- Working with device identifiers and external IDs
- Enriching data with device inventory information
- Handling different Cumulocity API types (measurements, events, alarms, inventory)
- Error handling and logging

### Using metadata in source templates and target templates {#metadata}

The mapper adds metadata in source and target templates to control the processing of the mapping. All JSON nodes
that are added as metadata to the templates are enclosed in `_`, e.g. `_CONTEXT_DATA_`, `_IDENTITY_` and
`_TOPIC_LEVEL_`.
They are automatically generated at runtime and removed before sending to Cumulocity. Common uses include:

- Extracting device identifiers from MQTT topics (`_TOPIC_LEVEL_`)
- Overriding target API endpoints (`_CONTEXT_DATA_.api`)
- Mapping external device IDs (`_IDENTITY_.externalId`)

:::caution
All metadata nodes including sub-nodes are not meant to be changed directly. All metadata nodes are generated
before the processing of a mapping and removed from the target payload before it is sent. Therefore, the metadata
is not saved in the mapping itself and cannot store individual information. To overwrite e.g. the API for a
mapping at runtime, you have to add a substitution: `[ 'EVENT' → _CONTEXT_DATA_.api ]`.

This section does not apply for mappings with transformation type **Smart Function**. For this transformation
type check out the code templates, which contains samples on how to achieve the same results using
[Smart Functions](/c8y-pkg-dynamic-mapper/introduction/smartfunction).
:::

![Change metadata](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Mapping_Stepper_Substitution_Change_Metadata.png "Screenshot showing additional substitution changing the target API to 'EVENT'")

The following table lists all metadata nodes for inbound mappings:

| Defined in template | Node | Role | Description |
|---|---|:---:|---|
| Source template (external broker) | `_TOPIC_LEVEL_[.]` | map-from | Topic of the inbound MQTT message. Can be used to identify a device if the topic contains external identifiers, e.g. serial number |
| Source template (external broker) | `_CONTEXT_DATA_.key` | map-from | Key from Kafka message header |
| Source template (MQTT 5 only) | `_CONTEXT_DATA_.clientId` | map-from | Client ID from MQTT 5 user properties. The publisher must include `clientId` as a user property when publishing the message. Not available for MQTT 3.1.1 connections. |
| Target template (Cumulocity) | `_IDENTITY_.externalId` | map-to | Map node from external template that identifies the device to this node |
| Target template (Cumulocity) | `_CONTEXT_DATA_.api` | map-to | Overwrite target API to send payload to, e.g. `ALARM` |
| Target template (Cumulocity) | `_CONTEXT_DATA_.processingMode` | map-to | Override the Cumulocity processing mode for this payload: `persistent` (default) — the object is stored in the database and persisted to disk, use for all critical data; `transient` — the object is processed and forwarded in real-time but **not written to the database**, use for high-frequency telemetry that must reach subscribed real-time consumers but does not need to be stored (reduces storage cost and write load). |
| Target template (Cumulocity) | `_CONTEXT_DATA_.deviceName` | map-to | Defines the device name of a device that is created implicitly when the mapping uses `Create non-existing devices` |
| Target template (Cumulocity) | `_CONTEXT_DATA_.deviceType` | map-to | Defines the device type of a device that is created implicitly when the mapping uses `Create non-existing devices` |

![Metadata inbound](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Mapping_Stepper_Mapping_Metadata_Inbound.png "Screenshot showing the metadata added for inbound mappings.")

:::info Info - MQTT 5 User Properties
**Publisher Client ID with MQTT 5:**

When using MQTT 5 connectors, publishers can include their client ID as a user property, which the mapper will
automatically extract and make available as `_CONTEXT_DATA_.clientId`. This allows you to identify which client
published a message.

**Example - Publisher side (MQTT 5):**

```java
Mqtt5Publish.builder()
    .topic("devices/data")
    .payload(payloadBytes)
    .userProperties()
        .add("clientId", "device-sensor-123")
        .applyUserProperties()
    .build();
```

**Example - Mapper side:**
In your mapping, you can then reference `_CONTEXT_DATA_.clientId` in the source template to access the
publisher's client ID. For example, you could map it to the external device ID:
`[ _CONTEXT_DATA_.clientId → _IDENTITY_.externalId ]`

**Note:** This feature is only available for MQTT 5 connections. MQTT 3.1.1 does not support user properties, so
the client ID must be included in the message payload or topic instead.
:::

The following table lists all metadata nodes for outbound mappings:

| Defined in template | Node | Role | Description |
|---|---|:---:|---|
| Source template (Cumulocity) | `_IDENTITY_.externalId` | map-from | External Id to identify the external device |
| Source template (Cumulocity) | `_IDENTITY_.c8ySourceId` | map-from | Cumulocity source id of the device |
| Target template (external broker) | `_TOPIC_LEVEL_[.]` | map-to | Topic to be used when sending messages. For a Webhook this defines the context path. The context path is then appended to the URL that is defined in the Webhook connector properties. This property has to be used for all transformation types other than Smart Functions, i.e. Substitution as JSONata Expression. |
| Target template (external broker) | `_CONTEXT_DATA_.key` | map-to | Key to be set in Kafka message header |
| Target template (external broker) | `_CONTEXT_DATA_.method` | map-to | REST methods to be set when using a Webhook connector |
| Target template (external broker) | `_CONTEXT_DATA_.retain` | map-to | Defines to send MQTT message as retained |
| Target template (external broker) | `_CONTEXT_DATA_.publishTopic` | map-to | Topic to be used when sending messages. For a Webhook this defines the context path. The context path is then appended to the URL that is defined in the Webhook connector properties. Supported for all transformation types. |

:::info
**Outbound Metadata Tips:**
- Use `_TOPIC_LEVEL_` to dynamically construct topics based on device properties
- Set `_CONTEXT_DATA_.method` to control HTTP methods (GET, POST, PUT, DELETE) for Webhook connectors
- Use `_CONTEXT_DATA_.retain` for MQTT to ensure last message is always available to new subscribers
:::

![Metadata outbound](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Mapping_Stepper_Mapping_Metadata_Outbound.png "Screenshot showing the metadata added for outbound mappings.")

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

:::info
**Understanding QoS Levels:**
- **QoS 0 (At most once):** Fastest, no delivery guarantee. Use for non-critical data where occasional loss is
  acceptable.
- **QoS 1 (At least once):** Guaranteed delivery, but duplicates possible. Recommended for most use cases.
- **QoS 2 (Exactly once):** Highest reliability, slowest. Use for critical data where duplicates must be avoided.
:::

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

:::info Info — "not relevant" in the table above
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
:::

### Managing permissions for Dynamic Mapper features {#access-control}

Dynamic Mapper uses role-based access control. The table below shows the permissions for each role:

- **No role**: Read-only access to mappings, connectors, and service configuration.
- **Create role**: All read permissions plus full mapping management (create, edit, delete, activate/deactivate,
  debug/filter).
- **Admin role**: All **Create role** permissions plus connector management and service configuration editing.

:::caution
**Security Best Practice:** Assign the minimum required role to users. Regular users typically need only
**Create role** to work with mappings. Reserve **Admin role** for system administrators who manage
infrastructure-level settings like connectors and service configuration.
:::

Key points:

- Only users with **Create role** or higher can modify mappings.
- Only users with **Admin role** can manage connectors or edit service configuration.
- All roles can read mappings, connectors, and service configuration.

| Dynamic Mapper Feature | No role | Create | Admin |
|---|:---:|:---:|:---:|
| **Mapping Read** | ✓ | ✓ | ✓ |
| **Mapping Create/Edit** | – | ✓ | ✓ |
| **Mapping Delete** | – | ✓ | ✓ |
| **Mapping Activate/Deactivate** | – | ✓ | ✓ |
| **Mapping Debug/Filter** | – | ✓ | ✓ |
| **Connector Read** | ✓ | ✓ | ✓ |
| **Connector Create/Edit** | – | – | ✓ |
| **Connector Delete** | – | – | ✓ |
| **Connector Activate/Deactivate** | – | – | ✓ |
| **Service Configuration Read** | ✓ | ✓ | ✓ |
| **Service Configuration Edit** | – | – | ✓ |

:::info
To configure roles, navigate to **Administration → Role Management** in Cumulocity. Look for "Dynamic Mapper"
permissions and assign them to global roles or specific user groups based on your organization's needs.
:::

### Monitoring {#monitoring}

The **Monitoring** section in the left navigation contains four views: **Statistic processed**, **Chart
processed**, **Cache statistic**, and **Service events**. Each gives a different operational perspective on what
the mapper is doing at runtime.

#### Statistic processed (Inbound / Outbound)

This view shows one row per mapping in a data grid. The columns are:

| Column | Meaning |
|---|---|
| **Name** | The mapping name. Click the name to open the mapping editor directly. |
| **Mapping topic** | The subscription topic pattern the mapping listens on (inbound) or the Cumulocity source object type (outbound). |
| **Publish topic** | The broker topic the mapping publishes to (outbound only). |
| **Received** | Total number of messages received and processed by this mapping since the last reset. Counts every message that matched the topic — including those that produced errors. |
| **Errors** | Number of messages that failed during transformation or Cumulocity API submission. A non-zero value means at least one message was dropped — check **Service events** to see the error detail. |

:::info
Counters accumulate since the last reset (or since microservice startup). Use the **Reset statistics** button in
the action bar to zero all counters — useful for measuring throughput during a specific time window. Counters are
lost on microservice restart.
:::

#### Chart processed

A time-series chart showing the number of messages processed per mapping over time. Each mapping appears as a
separate line. Use this view to spot traffic spikes, identify quiet mappings, and confirm that message flow
resumes after a connector reconnect. The chart updates live as messages arrive.

#### Cache statistic

The Cache statistic view shows three cache panels side by side, each displaying two KPI cards:

| Cache panel | KPI card | Meaning |
|---|:---:|---|
| **Inventory Cache** | **# Entries** | Number of managed object fragments currently held in memory. Below the card the configured size limit is shown (default: 100 000). When the limit is reached, the least-recently-used entry is evicted to make room. |
| **Inventory Cache** | **% Percent** | Fill rate — `Entries / Limit × 100`. A value approaching 100 % means the cache is nearly full and evictions are occurring frequently, which may slow down message processing. |
| **Inbound ID Cache** | **# Entries** | Number of external-ID → internal managed object ID mappings currently cached. Each mapping is resolved once and then stored here, so subsequent inbound messages skip the identity resolution REST call entirely. |
| **Inbound ID Cache** | **% Percent** | Fill rate for the inbound ID cache, same calculation as above. A high fill rate with many devices indicates you may want to increase the cache limit in the service configuration. |
| **Outbound ID Cache** | **# Entries** | Number of internal managed object ID (+ external ID type) → external-ID resolutions currently cached. Used by outbound mappings with **useExternalId** enabled — e.g. a Smart Function or Flow function reading `context.getConfig().externalId` — to avoid resolving the device's external ID from Cumulocity on every outbound message. |
| **Outbound ID Cache** | **% Percent** | Fill rate for the outbound ID cache, same calculation as above. |

The action bar at the top of the page provides four buttons:

- **Clear inbound external ID cache** — removes all cached external-ID-to-internal-ID resolutions (used by
  inbound mappings resolving a device from its external ID). Use this after deleting or re-registering a device
  to prevent stale identity lookups. The cache is rebuilt automatically as new messages arrive.
- **Clear outbound external ID cache** — removes all cached internal-ID-to-external-ID resolutions (used by
  outbound mappings with **useExternalId** enabled). Use this after re-enrolling a device under a different
  external ID, or reassigning an external ID to a different device, to avoid outbound messages being routed
  using a stale external ID. The cache is rebuilt automatically as new outbound messages arrive.
- **Clear inventory cache** — removes all cached managed object fragments. Use this after updating a device's
  managed object directly in Cumulocity (outside the mapper) so the mapper picks up the latest values. The
  fragments you configured under **Service Configuration → Function → Fragments from inventory to cache** are
  re-fetched on demand. Entries can be exact fragment names or glob patterns (e.g. `sparkPlugB_DBIRTH_*`).
- **Reload** — refreshes the KPI cards without clearing the caches, useful to get an up-to-date snapshot of the
  current fill levels.

:::caution
All three caches are held in memory and are lost on microservice restart. After a restart they are rebuilt
automatically as messages arrive — no manual action is needed.
:::

:::info
Both ID caches are also cleared automatically on a configurable schedule — see **Days lifetime inbound Id
cache** and **Days lifetime outbound Id cache** under
[**Service Configuration → Caching**](/c8y-pkg-dynamic-mapper/node3/serviceConfiguration/caching). This is a
full-cache wipe on a timer (not per-entry expiry), so set the retention short enough for your device
re-enrollment/reassignment cadence if you rely on it instead of manually clearing the cache.
:::

#### Service events

The Service events view is the primary place to diagnose individual message failures without needing access to
the microservice container logs. It displays a filterable list of events emitted by the mapper backend. Each
entry shows:

- **Type** — event severity/category (e.g. `STATUS_MAPPING_CHANGED`, `STATUS_CONNECTOR_EVENT`,
  `MAPPING_FAILURE`)
- **Timestamp** — when the event occurred
- **Message** — the full error or status description, including the mapping name, tenant, and root cause

Use the **Type** dropdown and **Date from / Date to** pickers to narrow the event list to a specific time window
or event category. This is particularly useful when correlating errors with known message arrival times.

:::caution
Service events are stored in an in-memory ring buffer and are lost on microservice restart. For long-term audit
trails, configure the Cumulocity platform's built-in audit log or forward events to an external monitoring
system.
:::

### Message Explorer {#message-explorer}

The **Message Explorer** lets you capture and inspect live messages flowing through the Dynamic Mapper — before
any mapping transformation is applied. It is a powerful tool for understanding your device payloads, validating
connector subscriptions, and rapidly building new mappings from real traffic.

:::info
The Message Explorer is available under
[**Mappings → Message Explorer**](/c8y-pkg-dynamic-mapper/node1/mappings/messageExplorer). No mapping or
transformation is applied — you see the raw payloads exactly as they arrive from the broker or the Cumulocity
Notification 2.0 API.
:::

![Message Explorer](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Mapping_Message_Explorer.png "Screenshot of the Message Explorer showing captured live messages from a broker topic.")

#### Starting a session

Click **Start exploring messages…** in the action bar to open the session configuration drawer. Configure the
following settings:

| Setting | Description |
|---|---|
| **Direction** | Inbound — subscribe to a broker topic via a selected connector. Raw payloads arriving on the topic are captured. Outbound — subscribe to the Cumulocity Notification 2.0 API for a selected device or group and capture the raw JSON objects before they are transformed by an outbound mapping. |
| **Connector** (Inbound only) | The connector to subscribe to. Only connectors that support the selected direction and are currently connected are selectable. |
| **Topic** (Inbound only) | The broker topic to subscribe to. MQTT wildcards (`#`, `+`) are supported. Be specific to avoid capturing unrelated traffic. |
| **Source device / group** (Outbound only) | The managed object (device or group) for which a Notification 2.0 subscription is created. Selecting a group captures events for all devices in that group. |
| **Max messages to buffer** | Maximum number of messages kept in the in-browser buffer (1–500). When the limit is reached, the oldest messages are discarded automatically. Reduce this value for high-frequency topics. |

Click **Start** to open the session. The action bar switches to show **Stop session**, **Pause / Resume**, and
**Clear** controls.

#### Viewing captured messages

Each captured message appears as a row in the message list with the following columns:

| # | Column | Description |
|:---:|---|---|
| 1 | **Direction** | INBOUND or OUTBOUND badge. |
| 2 | **Received at** | Timestamp when the backend captured the message. |
| 3 | **Connector** | Name of the connector that delivered the message. |
| 4 | **Topic** | The exact broker topic the message arrived on (wildcards resolved). |
| 5 | **Payload** | Truncated payload preview. Click **show more** to expand the row and view the full payload in a formatted JSON editor. Binary payloads are flagged with a "binary (base64)" badge. |
| 6 | **Create mapping** | The button opens the **Add Mapping** wizard pre-filled with the captured payload as the source template. This is the fastest way to build a mapping from real device data. |

#### Session controls

The action bar provides the following controls while a session is active:

- **Stop session** — terminates the backend subscription and ends the session. All captured messages remain
  visible in the list until you navigate away or click **Clear**.
- **Pause** — suspends polling of new messages. The backend session stays alive so no messages are missed;
  messages received while paused are returned when you resume.
- **Resume** — resumes polling and appends any messages that arrived while paused.
- **Clear** — removes all messages from the in-browser list without stopping the session. Use this to start a
  fresh capture window during an active session.
- **Auto refresh** — when enabled, the Message Explorer polls the backend at the configured interval (e.g. every
  5 s) and appends newly arrived messages automatically.

#### Creating a mapping from a captured message

The most powerful feature of the Message Explorer is the ability to instantly build a mapping from live traffic:

1. Start a session on a connector and topic that your devices publish to.
2. Wait for at least one message to appear in the list.
3. Click the "add" button on the message row you want to use as a template.
4. The **Add Mapping** dialog opens with the captured payload pre-loaded as the source template, and the topic
   pre-filled from the captured message.
5. Complete the mapping wizard as usual — the source template is already populated, so you can skip straight to
   defining substitutions.

:::info Tip
Combine the Message Explorer with **AI-powered substitution generation**: capture a real payload, create a
mapping from it, and then use the AI prompt in the substitution step to auto-generate the mapping rules from a
natural-language description.
:::

### Troubleshooting {#troubleshooting}

The following lists common problems and how to resolve them.

#### Messages arrive but no Cumulocity objects are created

- Check that the mapping is **enabled** (activated). A greyed-out mapping icon means it is disabled.
- Verify the **topic pattern** matches the topic your device is publishing to. Wildcards `#` and `+` follow MQTT
  semantics.
- Open [**Monitoring → Statistics**](/c8y-pkg-dynamic-mapper/node2/monitoring/statistic/inbound) and check
  whether the message counter for the mapping increases. If it does not, the topic pattern is not matching.
- If the counter increases but objects are not created, check the **Event Log** for transformation errors.

#### Transformation errors in the Event Log

- **"Device not found"** — the `externalId` returned by your mapping does not match any registered device.
  Enable **Create non-existing devices** on the mapping or pre-register the device in Cumulocity.
- **"Cannot evaluate expression"** (JSONata) — the JSONata expression references a field that is missing or null
  in the source payload. Add a null-guard, e.g. `payload.value ? payload.value : 0`.
- **Smart Function throws a JavaScript exception** — the full stack trace is shown in the Event Log. Use
  `console.log()` in your function to emit diagnostic output visible in the Event Log.
- **"Fragment not found in cache"** — the managed object fragment accessed via `context.getManagedObject()` is
  not in the [**Fragments from inventory to cache**](/c8y-pkg-dynamic-mapper/node3/serviceConfiguration/general)
  list. Add the exact fragment name or a matching glob pattern (e.g. `sparkPlugB_DBIRTH_*`) and clear the
  inventory cache or wait for it to refresh.

#### Outbound mapping does not trigger

- Check the mapping's **Source API** — it is used as a filter to select which mappings apply when a
  notification arrives. If it does not match the API of the incoming notification (e.g. **Measurement** vs.
  **Event**), the mapping is silently ignored for that notification.
- Verify that a **subscription** exists for the device. Without a subscription, no outbound messages are
  processed regardless of mapping state.
- Check the **Inventory Filter** — if set, it must evaluate to `true` for the device's managed object.
- Check the **Execution Filter** — if set, it must evaluate to `true` for the triggering payload.
- Confirm the mapping's **connector** is connected (green status in
  [**Monitoring → Statistics**](/c8y-pkg-dynamic-mapper/node2/monitoring/statistic/inbound)).
- If the mapping has **useExternalId** enabled and reads `context.getConfig().externalId` in its
  Smart Function/Flow code, a stale entry in the **Outbound ID Cache** (e.g. after re-enrolling the device under
  a different external ID) can route the message using the old external ID. Clear it under
  [**Monitoring → Cache statistic**](/c8y-pkg-dynamic-mapper/node2/monitoring/cache) using
  **Clear outbound external ID cache**.

#### Smart Function returns stale device inventory data

- The mapper caches inventory fragments for performance. Navigate to
  [**Service Configuration → Caching**](/c8y-pkg-dynamic-mapper/node3/serviceConfiguration/general) and click
  **Clear inventory cache** to force a refresh.
- Ensure the required fragment is listed in
  [**Service Configuration → Function → Fragments from inventory to cache**](/c8y-pkg-dynamic-mapper/node3/serviceConfiguration/general).
  Only listed fragments (or fragments matching a configured glob pattern, e.g. `sparkPlugB_DBIRTH_*`) are loaded
  into the cache.

#### Connector disconnects repeatedly

- Check the broker's access logs for authentication or authorization errors.
- Verify the connector credentials, host, and port in the connector configuration.
- For TLS connections, ensure the broker's certificate is trusted and the CA certificate is correctly configured
  in the connector.
- Check the Event Log for TLS handshake or connection timeout errors.

#### Enabling debug mode for a mapping

Debug mode logs detailed processing information for a specific mapping without enabling verbose logging globally.
In the mapping list, use the context menu (three-dot icon) to enable **Debug** for a mapping. Debug output
appears in the **Event Log** and in the microservice logs. Disable debug mode after troubleshooting to avoid
excessive log volume.

:::caution
Debug mode logs full payload content. Do not leave it enabled in production if payloads contain personally
identifiable information (PII) or secrets.
:::
