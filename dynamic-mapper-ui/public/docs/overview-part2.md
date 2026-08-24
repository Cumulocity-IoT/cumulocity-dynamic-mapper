The Dynamic Mapper is **AI empowered**.

:::info AI-Powered Development
The Dynamic Mapper can leverage AI to automatically generate mapping rules from your payload examples. Simply
describe what you want to achieve in natural language, and the AI generates the corresponding JSONata expressions
or JavaScript code.

In the **Service Configuration → AI** section, assign an AI agent (defined in the Cumulocity AI Agent Manager)
separately for JSONata, JavaScript, and Smart Function transformations. This significantly accelerates the
integration process.
:::

When you configure AI agents in **Service Configuration → AI**, substitutions or JavaScript code can be generated
automatically. Each transformation type (JSONata, JavaScript, Smart Function) can use a different agent. The
underlying AI provider (Anthropic, OpenAI, etc.) is configured in the AI Agent Manager, not here. The use and
configuration of the Cumulocity AI Agent Manager is introduced
[here](https://community.cumulocity.com/t/introducing-the-ai-agent-manager-powering-enterprise-aiot-on-cumulocity/12567).
The following screenshot shows a prompt to generate a list of substitutions (mapping rules):

![Substitution annotation](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Mapping_Stepper_Substitution_Generate_JSONata.png "Screenshot showing a prompt to generate a list of substitutions (mapping rules).")

### Getting started {#getting-started}

To begin using the Dynamic Mapper, follow these two essential steps to establish connectivity and define your data
transformations:

#### Step 1: Add a Connector

First, create a connector to establish communication with your message broker or data source. The Dynamic Mapper
supports various connector types:

- **External MQTT Broker** - Connect to third-party MQTT brokers such as HiveMQ, Mosquitto, Eclipse Mosquitto, or
  any MQTT-compliant broker
- **Cumulocity MQTT Service** - Use the built-in Cumulocity IoT MQTT broker for device communication with device
  isolation
- **HTTP Connector** - Receive data via REST endpoints
- **Webhook** - Send data to external REST APIs
- **Cumulocity API** - Use the internal Cumulocity REST API for creating, updating, and deleting managed objects,
  events, alarms, and measurements
- **Apache Kafka** - Integrate with Kafka topics for high-throughput messaging
- **Apache Pulsar** - Connect to Pulsar topics for cloud-native messaging
- **AMQP 0-9-1** - Connect to AMQP 0-9-1 brokers like RabbitMQ for reliable message queuing
- **AMQP 1.0** - Connect to AMQP 1.0 brokers (Azure Service Bus, ActiveMQ Artemis, Solace, etc.) using the Apache
  Qpid JMS client

Use the [wizard](/c8y-pkg-dynamic-mapper/node3/connectorConfiguration) and **Add Connector** to create your
connector. Refer to the [Managing Connectors](#managing-connectors) section for detailed guidance.

#### Step 2: Create a Mapping

After establishing connectivity, define a mapping to transform your data. When creating a mapping, you need to
make two key decisions:

:::info Info — Expert Mode
By default the mapping creation dialog shows only the **JSON** payload type with the standard transformation
types. Enable **Expert Mode** in the dialog to unlock all payload formats (Flat File, Hexadecimal, Protobuf, Any,
Extension) and the full list of transformation types.
:::

##### Step 2.1: Select the Payload Type

Choose the format that matches your incoming or outgoing data:

- **JSON** - The most common format for structured data (recommended for most use cases)
- **Flat File (CSV)** - For comma-separated or delimiter-based data (requires Expert Mode)
- **Hexadecimal** - For binary data encoded as hex strings (requires Expert Mode)
- **Protobuf** - For Protocol Buffer serialized data (requires Expert Mode)
- **Any Payload** - Payload format is unknown or binary (e.g. CBOR, XML, custom binary). Passed as a
  Base64-encoded string; processed by a **Smart Function** or a **Java Extension**. (requires Expert Mode)
- **SparkPlug B** - Native support for the Eclipse Sparkplug B protocol over MQTT. The binary protobuf payload is
  automatically decoded for inbound; outbound NCMD/DCMD messages are serialized to protobuf binary. Use a
  **Smart Function** in both directions. (requires Expert Mode)

##### Step 2.2: Choose the Transformation Type

The Transformation Type selector is visible only when **Expert Mode** is enabled. Select how you want to define
the data transformation logic:

- **JSONata Expressions** - Use declarative JSONata expressions for straightforward field mappings and
  transformations. Ideal for simple to moderately complex transformations without programming knowledge.
- **Smart Function (JavaScript)** - Define the complete transformation logic using JavaScript. Offers maximum
  control and flexibility for complex transformations, calculations, and business logic. Allows access to device
  context and inventory data for enrichment.

If you're using **Smart Function (JavaScript)** as Transformation Type, navigate to
[Code Templates](/c8y-pkg-dynamic-mapper/node3/codeTemplate/INBOUND_SMART_FUNCTION) to see JavaScript code samples
of transformations.

To add a mapping navigate to [**Inbound → Add Mapping**](/c8y-pkg-dynamic-mapper/node1/mappings/inbound) (or
[**Outbound → Add Mapping**](/c8y-pkg-dynamic-mapper/node1/mappings/outbound)). The intuitive stepper wizard will
guide you through the mapping configuration process.

:::info
If you're unsure about your device's payload structure, use the **Message Explorer** to inspect incoming messages
in real time and use a captured payload directly as the source template when creating a mapping. See
[Message Explorer](#message-explorer) for details.
:::

**Next Steps:**

- Not sure what payload your devices send? Use the [Message Explorer](#message-explorer) to capture live messages
  from a connector topic and instantly build a mapping from real traffic.
- Review [Defining a Mapping](#define-mapping) for step-by-step instructions
- Explore [JSONata Substitutions](#jsonata-substitution) for expression-based transformations
- Learn about [Smart Functions](#javascript-smart-function) for advanced scenarios

### Managing connectors {#managing-connectors}

The first step when working with the Dynamic Mapper is to create a connector. Select based on your message broker
or integration requirements.
For IoT devices, MQTT connectors are most common. For enterprise integrations, consider Kafka or HTTP connectors.
Webhooks are ideal for outbound integrations to external systems, while the Cumulocity API connector is for
internal Cumulocity REST API operations.
The mapper supports the following connectors and payload formats:

| Connector | Inbound | Outbound | JavaScript | Supported Payload Formats |
|---|:---:|:---:|:---:|---|
| **Cumulocity MQTT Service** (device isolation, one instance per tenant) | ✓ | ✓ | ✓ | JSON, Hex, Protobuf, Extension |
| **MQTT** | ✓ | ✓ | ✓ | JSON, Hex, Protobuf, Extension, **SparkPlug B** |
| **HTTP Connector** (one instance per tenant) | ✓ | – | ✓ | JSON, Hex, Protobuf, Extension |
| **Webhook** (for external REST APIs) | – | ✓ | ✓ | JSON |
| **Cumulocity API** (for internal Cumulocity REST API) | – | ✓ | ✓ | JSON |
| **Apache Pulsar** | ✓ | ✓ | ✓ | JSON, Hex, Protobuf, Extension |
| **Kafka** | ✓ | ✓ | ✓ | JSON, Hex, Protobuf, Extension |
| **AMQP 0-9-1** (RabbitMQ, etc.) | ✓ | ✓ | ✓ | JSON, Hex, Protobuf, Extension |
| **AMQP 1.0** (Azure Service Bus, Artemis, Solace, etc.) | ✓ | ✓ | ✓ | JSON, Hex, Protobuf, Extension |

:::caution
Some connectors like HTTP Connector and Cumulocity MQTT Service have only one instance per tenant. Multiple
mappings can share the same connector instance. Configure the connector properties carefully as they affect all
associated mappings.
:::

Add a new connector using the following wizard
[**Configuration → Connectors → Add connector**](/c8y-pkg-dynamic-mapper/node3/connectorConfiguration).

![Payload type](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Connector_New.png "Creating a new connector.")

The **Webhook** connector has a setting **Cumulocity Internal** which can be used when Cumulocity MEA should be
processed and sent back to Cumulocity Core as transformed MEA, e.g. receive an `EVENT` of type `c8y_Uplink` and use
a **SMART_FUNCTION** to decode the payload and transform it into a `MEASUREMENT`.

### Defining a mapping {#define-mapping}

When you start with a new mapping, the first considerations are about the payload format and the transformation
type to use:

1. In which format is the inbound payload sent? This defines the payload type to choose: JSON, Flat File,
   Hexadecimal, Protobuf
2. How to define the transformation of inbound to Cumulocity format? This defines the transformation type:
   JSONata, Smart Functions, ...

:::important
If any of the templates: source or target is a JSON array - instead of a JSON object - then you have to choose
**Smart Function** as a transformation type.

This is due to the internal handling of metadata, e.g. `_IDENTITY_.externalId` that is added to the template. This
is not possible for JSON arrays.
:::

Now you start adding a mapping by clicking [Inbound](/c8y-pkg-dynamic-mapper/node1/mappings/inbound) **Add
Mapping**.
The following two screenshots show the selection of the **Payload Type** and **Transformation Type**.

![Payload type](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Mapping_Table_Add_Modal_Payload.png "Screenshot showing available payload types.")

![Transformation type](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Mapping_Table_Add_Modal_TransformationType.png "Screenshot showing available transformation types.")

#### Payload Types in Detail {#payload-types-detail}

The following describes each payload type and how it is processed by the mapper:

##### JSON (default)

The message body is parsed as a JSON object. Recommended for most IoT integrations. All substitution-based
transformation types (JSONata, JavaScript) and Smart Functions operate on the parsed JSON object.

##### Flat File (CSV) (Expert Mode)

The message body is a delimited text file (comma, semicolon, tab, or custom character). The mapper converts each
line into a JSON array of field values before applying substitutions. Configure the delimiter in the mapping's
general settings. Field values are then accessible as `payload[0]`, `payload[1]`, etc. Example: a CSV line
`sensor01,23.5,°C` becomes `["sensor01", "23.5", "°C"]`.

##### Hexadecimal (Expert Mode)

The message body is a hex-encoded string (e.g. `68656C6C6F`). The mapper passes the raw hex string to your
transformation. In a Smart Function receive it via `msg.getPayload()` and decode it using the built-in `atob()` /
`btoa()` helpers (see [Binary helpers in Smart Functions](#binary-helpers)).

##### Protobuf (Expert Mode)

Protocol Buffer binary messages. The mapper deserializes the Protobuf payload into a JSON representation using the
schema registered in the mapping. Because Protobuf requires a compiled schema, this payload type is typically used
together with a **Java Extension** that performs the schema-aware deserialization.

##### Any Payload (Expert Mode)

Use this when the payload format is unknown, binary (CBOR, XML, custom binary), or when you want full programmatic
control. The raw payload bytes are passed to your transformation as a Base64-encoded string. You must use a
**Smart Function** or **Java Extension** that decodes and interprets the bytes. In a Smart Function, access the
raw data via `msg.getPayload()` and decode it with `atob()`.

##### SparkPlug B (Expert Mode)

Native first-class support for the [Eclipse Sparkplug B](https://sparkplug.eclipse.org/) protocol over MQTT. For
**inbound** mappings the binary protobuf payload is automatically decoded by the mapper using the
[Eclipse Tahu](https://github.com/eclipse-tahu/tahu) library — no manual Base64 decoding required. For **outbound**
(NCMD / DCMD) mappings, the metric object returned by your Smart Function is automatically serialized to protobuf
binary before publishing. See the [SparkPlug B](#sparkplugb) section for the full protocol details, message types,
and Smart Function API.

:::important
**Payload type and transformation type cannot be changed after mapping creation.** If you need a different type,
delete the mapping and create a new one. To avoid this, enable **Expert Mode** in the creation dialog to see all
options upfront.
:::

The stepper guides you through these steps to define a mapping using JSONata for substitutions:

1. Add or select an existing connector for your mapping (where payloads come from).
2. Define general settings, such as the topic name for this mapping.
3. Select or enter the template for the expected source payload. This is used as the source path for
   substitutions.
4. Transformation for copying content from the source to the target payload. These will be applied at runtime.
5. Test the mapping by applying the substitutions and save the mapping.

In the second step of the wizard you define the most important properties for the mapping, e.g. Mapping Name,
Target API, Mapping Topic (topic to which this mapping should listen for, this supports wildcards: `#`, `+`). The
Mapping Topic Sample is a sample topic replacing all wildcards from the Mapping Topic, e.g. `datalogger/+` becomes
`datalogger/logger_13579`, this helps in the later steps to use concrete values instead of the abstract wildcards.

![Mapping stepper properties](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Mapping_Stepper_Topic_Definition.png "Screenshot of second wizard step to define general properties.")

:::info
**Best Practice:** Always test your mapping with sample payloads before activating it. Use the test feature in
step 5 to verify that your substitutions produce the expected Cumulocity format. This helps catch errors early and
ensures smooth operation.
:::

The following screenshot shows the **Transformation** step for transformation type **Substitution as JSONata
Expression**. This step shows a JavaScript editor if you choose **Smart Function (JavaScript)**.

![Substitution stepper](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Mapping_Stepper_Substitution_Basic.png "Screenshot of fourth wizard step to define substitutions using JSONata expressions.")

### SparkPlug B {#sparkplugb}

The Dynamic Mapper provides native, first-class support for the
[Eclipse Sparkplug B](https://sparkplug.eclipse.org/) protocol — a standardized MQTT-based messaging specification
designed for Industrial IoT (IIoT) environments. Sparkplug B defines a strict topic namespace, payload encoding
(protobuf), and a birth/data/death message lifecycle that the mapper understands and handles automatically.

:::info Inbound & Outbound
The **SparkPlug B** payload type is supported for both **inbound** and **outbound** mappings and requires a
**MQTT** connector (MQTT Service support only partially due to missing retain messages). Inbound decodes NBIRTH /
NDATA / DBIRTH / DDATA / NCMD / DCMD protobuf payloads automatically. Outbound serializes the metric object
returned by your Smart Function to SparkPlug B protobuf binary before publishing NCMD or DCMD messages to the
broker.
:::

#### Topic Structure

Sparkplug B topics follow the fixed format: `spBv1.0/[Group ID]/[Message Type]/[Edge Node ID]/[Device ID]`

| Message Type | Topic Levels | MO Type (C8Y) | Fragment Stored | Description |
|---|:---:|:---:|---|---|
| `NBIRTH` | 4 | `c8y_Serial` | `sparkPlugB_NBIRTH` | Edge Node birth — metric definitions published when the node comes online. The mapper stores the alias→metric-definition map on the Edge Node managed object so that subsequent NDATA messages can resolve aliases. The Edge Node external ID is `[Group ID]_[Edge Node ID]`. |
| `NDATA` | 4 | — | — | Edge Node data — metric values published periodically. Aliases are resolved using the `sparkPlugB_NBIRTH` fragment stored on the Edge Node MO. The decoded payload is passed to your Smart Function. |
| `DBIRTH` | 5 | `c8y_Serial` | `sparkPlugB_DBIRTH_[deviceId]` | Device birth — metric definitions for a device attached to an Edge Node. Per default no managed objects are created for SparkPlug devices. The mapper stores the alias→metric-definition map and metrics on the Edge node managed object so that subsequent DDATA messages can resolve aliases. |
| `DDATA` | 5 | — | — | Device data — metric values for a specific device. Aliases are resolved using the `sparkPlugB_DBIRTH_[deviceId]` fragment stored on the Edge Node MO. The decoded payload is passed to your Smart Function. |
| `NDEATH` / `DDEATH` | 4 / 5 | — | `sparkPlugB_IsActive_[deviceId]` | Node/Device death — signals that the node or device has gone offline. The fragment `sparkPlugB_IsActive_[deviceId]` for DDEATH or `sparkPlugB_IsActive` for NDEATH will be set to false. Passed to the Smart Function without alias resolution. |
| `NCMD` / `DCMD` | 4 / 5 | — | — | Node/Device command — command messages sent to a node or device. **Inbound:** aliases are resolved like NDATA/DDATA respectively and passed to the Smart Function. **Outbound:** the metric object returned by the Smart Function is serialized to protobuf binary by `SparkPlugBSerializer` before publishing. Use the `context.getConfig().aliasMap` to include numeric aliases in the outbound metrics. |

#### How the Mapper Processes SparkPlug B Messages

##### Inbound

1. **Deserialization** — The binary protobuf payload is decoded automatically by the mapper using the
   [Eclipse Tahu](https://github.com/eclipse-tahu/tahu) library. No manual decoding is needed in your Smart
   Function.
2. **Birth message storage (NBIRTH / DBIRTH)** — After the managed object for the Edge Node is upserted in
   inventory, the decoded metric-definition map (alias → { name, dataType }) is stored as a named fragment on the
   MO. This happens automatically — your Smart Function does not need to handle it.
3. **Alias resolution (NDATA / DDATA)** — Sparkplug B data messages typically use numeric aliases instead of full
   metric names to reduce payload size. The mapper looks up the stored birth fragment and replaces aliases with
   their original names before passing the payload to your Smart Function.
4. **Smart Function execution** — Your Smart Function receives the fully decoded and alias-resolved payload as a
   JavaScript object and returns one or more `CumulocityObject` instances.

##### Outbound (NCMD / DCMD)

1. **Enrichment** — The mapper resolves the device external ID and loads the `sparkPlugB_NBIRTH` (or
   `sparkPlugB_DBIRTH_[deviceId]`) alias map from the managed object. The inverted map (metric name → alias) is
   available inside the Smart Function as `context.getConfig().aliasMap`.
2. **Smart Function execution** — Your `onMessage(msg, context)` function receives the triggering Cumulocity
   payload (e.g. an operation) and returns an object with `topic` and `payload` fields. The topic must be a valid
   SparkPlug B NCMD or DCMD topic (`spBv1.0/<GroupID>/NCMD/<EdgeNodeID>`). The payload is a plain JavaScript object
   with an optional `seq`, `timestamp`, and a `metrics` array.
3. **Serialization** — The mapper's `SparkPlugBSerializer` converts the returned metric object to SparkPlug B
   protobuf binary bytes before publishing to the broker. Include `alias` values in the metric entries (available
   from `context.getConfig().aliasMap`) to produce compact messages that the edge node can match by alias.

#### Decoded Payload Structure

The object passed to your Smart Function via `msg.getPayload()` has the following shape:

```
{
  "messageType": "NDATA",        // NBIRTH | NDATA | DBIRTH | DDATA | NDEATH | DDEATH | NCMD | DCMD
  "groupId":     "factory-01",   // Sparkplug Group ID
  "edgeNodeId":  "plc-01",       // Edge Node ID (always present)
  "deviceId":    "sensor-a",     // Device ID (present for D* messages only)
  "timestamp":   1713700000000,  // UTC milliseconds
  "seq":         42,             // Sequence number
  "metrics": [
    {
      "name":      "Temperature",   // resolved from alias (NDATA/DDATA) or original (NBIRTH/DBIRTH)
      "alias":     12,
      "dataType":  "Float",
      "floatValue": 23.5,
      "timestamp": 1713700000000
    }
  ],
  "sparkPlugB_NBIRTH": { ... }   // birth map attached for NBIRTH / NDATA (sparkPlugB_DBIRTH_[deviceId] for DBIRTH / DDATA)
}
```

#### Creating a SparkPlug B Mapping

1. **Connector** — Create a **MQTT** connector pointing to your MQTT broker that receives Sparkplug B messages.
2. **Add Mapping** — For inbound, navigate to
   [Inbound → Add Mapping](/c8y-pkg-dynamic-mapper/node1/mappings/inbound). For outbound NCMD/DCMD, navigate to
   [Outbound → Add Mapping](/c8y-pkg-dynamic-mapper/node1/mappings/outbound). Enable **Expert Mode** in the
   dialog.
3. **Payload Type** — Select **SparkPlug B**.
4. **Transformation Type** — **Smart Function (JavaScript)** is the only available option for this payload type.
5. **Topic** — Enter the Sparkplug B topic pattern, e.g. `spBv1.0/factory-01/#` to receive all message types from
   a group, or a more specific pattern such as `spBv1.0/factory-01/NDATA/plc-01`.
6. **Smart Function** — Write a `onMessage(msg, context)` function that processes the decoded payload and returns
   `CumulocityObject` instances. See the example below.

#### Birth Message Handling

:::info How birth fragments are stored
For **NBIRTH** and **DBIRTH** messages the mapper stores the decoded metric-definition map as a fragment on the
corresponding managed object so that subsequent NDATA/DDATA messages can resolve metric aliases. The storage logic
works in two steps:

1. **Smart Function returns an INVENTORY object** — the mapper upserts the MO (creates or updates it) and
   immediately stores the birth fragment on it. This is the recommended path for the *first boot* of a node or
   device.
2. **Smart Function returns nothing (or a non-INVENTORY object)** — the mapper derives the external ID directly
   from the topic (name `c8y_Serial`, value `[Group ID]_[Edge Node ID]` for NBIRTH; name `c8y_Serial`, value
   `[Group ID]_[Edge Node ID]_[Device ID]` for DBIRTH) and looks up the pre-existing MO in inventory. If found,
   the birth fragment is stored on it. This covers re-boots of already-registered nodes/devices without requiring
   any INVENTORY object from the Smart Function.

If the MO does not exist and the Smart Function does not create it, the mapper checks the
`createNonExistingDevice` flag. If enabled, a minimal MO is auto-created with the external ID derived from the
topic and the birth fragment is stored on it. Otherwise an error is logged and the birth fragment cannot be
stored — causing all subsequent alias lookups to fail.
:::

:::important Important — first boot
On the **first ever boot** of an Edge Node or Device (i.e. before a MO exists in inventory), choose one of two
approaches:

- Enable **createNonExistingDevice** on the mapping — the mapper automatically creates a minimal MO using the
  external ID derived from the topic. This is the simplest option and requires no changes to the Smart Function.
- Return a `CumulocityObject` with `cumulocityType: "managedObject"` from the Smart Function — use this when you
  need a custom device name, type, or additional fragments on the managed object.

For subsequent reboots the fallback lookup handles fragment storage automatically even if the Smart Function
returns nothing for the birth message.
:::

#### Smart Function Example — Inbound

The following example handles all Sparkplug B message types and maps NDATA temperature metrics to Cumulocity
measurements:

```javascript
function onMessage(msg, context) {
  var payload  = msg.getPayload();
  var msgType  = payload.messageType;  // e.g. "NBIRTH", "NDATA", "DBIRTH", "DDATA"
  var groupId  = payload.groupId;
  var nodeId   = payload.edgeNodeId;
  var deviceId = payload.deviceId;     // undefined for node-level messages

  // External IDs follow the Sparkplug B namespace to ensure global uniqueness:
  //   Edge Node: [Group ID]_[Edge Node ID]
  //   Device:    [Group ID]_[Edge Node ID]_[Device ID]
  var nodeExtId   = groupId + '_' + nodeId;
  var deviceExtId = deviceId ? groupId + '_' + nodeId + '_' + deviceId : undefined;

  // ── Birth messages: create / update the managed object ──────────────────────
  if (msgType === 'NBIRTH' || msgType === 'DBIRTH') {
    var extId = msgType === 'NBIRTH' ? nodeExtId : deviceExtId;
    return [{
      cumulocityType: 'managedObject',
      externalSource: [{ externalId: extId, type: 'c8y_Serial' }],
      payload: {
        name: extId,
        type: 'c8y_Serial'
      }
    }];
  }

  // ── NDATA / DDATA: map metrics to measurements ───────────────────────────────
  if (msgType === 'NDATA' || msgType === 'DDATA') {
    var extId   = msgType === 'DDATA' ? deviceExtId : nodeExtId;
    var results = [];
    (payload.metrics || []).forEach(function(metric) {
      if (metric.name === 'Temperature') {
        results.push({
          cumulocityType: 'measurement',
          externalSource: [{ externalId: extId, type: 'c8y_Serial' }],
          payload: {
            type: 'c8y_Temperature',
            time: new Date(payload.timestamp).toISOString(),
            'c8y_Temperature': { T: { value: metric.floatValue, unit: '°C' } }
          }
        });
      }
    });
    return results;
  }

  // Death / CMD messages — nothing to do in this example
  return [];
}
```

#### Smart Function Example — Outbound NCMD

The following example sends an NCMD (Node Command) from a Cumulocity operation. The mapper serializes the
returned metric object to SparkPlug B protobuf binary automatically.

`context.getConfig().aliasMap` is a `{ metricName → alias }` map automatically loaded from the
`sparkPlugB_NBIRTH` (or `sparkPlugB_DBIRTH`) fragment stored on the device managed object during inbound BIRTH
processing. Including the numeric alias in each outbound metric lets the edge node match it without a full name
lookup, reducing payload size. The `metric()` helper below adds the alias only when one is available.

`context.getConfig().isActive` reflects whether the Edge Node or Device is currently online (`true` after NBIRTH /
NDATA, `false` after NDEATH). Use it to suppress commands to offline devices. Defaults to `true` when no status
has been recorded yet.

```javascript
function onMessage(msg, context) {
  // context.getConfig().externalId is "GroupID_EdgeNodeID" when useExternalId is enabled.
  const externalId = context.getConfig().externalId;
  const parts      = externalId ? externalId.split('_') : [];
  const groupId    = parts[0] || 'DefaultGroup';
  const edgeNodeId = parts[1] || 'DefaultNode';

  // Suppress commands to offline edge nodes (NDEATH received)
  if (!context.getConfig().isActive) {
    return null;
  }

  // aliasMap: metric name → alias string (loaded from sparkPlugB_NBIRTH on the MO)
  const aliasMap = context.getConfig().aliasMap || {};

  // Helper: build a metric entry and add alias when available
  function metric(name, type, value) {
    const entry = { name, type, value };
    if (aliasMap[name] !== undefined) {
      entry.alias = parseInt(aliasMap[name], 10);
    }
    return entry;
  }

  return {
    // NCMD topic: spBv1.0/<GroupID>/NCMD/<EdgeNodeID>
    topic: `spBv1.0/${groupId}/NCMD/${edgeNodeId}`,
    payload: {
      timestamp: Date.now(),
      metrics: [
        metric('Node Control/Rebirth', 'Boolean', false)
      ]
    }
  };
}
```

:::info Supported metric types
The `type` field in each metric entry is case-insensitive and supports: `Int8`, `Int16`, `Int32`, `Int64`,
`UInt8`, `UInt16`, `UInt32`, `UInt64`, `Float`, `Double`, `Boolean`, `String`, `DateTime`, `Text`, `UUID`, `Bytes`.
:::

### Defining a subscription for outbound mapping {#define-subscription-for-outbound}

When defining an outbound mapping, the **Dynamic Mapper backend** needs to be triggered when data for a device is
updated.
This only happens when you created a subscription for the device. The details on how to create subscriptions is
explained later in this section.

:::important
Outbound mappings require active subscriptions. Without a subscription, the mapping will not be triggered even if
it's enabled. Make sure to configure subscriptions properly for your device groups or device types.
:::

An outbound mapping is processed only if all of the following conditions apply:

- A subscription for this device exists
- If an inventory filter is defined for the mapping, e.g. `type == "pressure_sensor"`, it must evaluate to `true`
- The mapping must be enabled (activated) and a connector must be assigned to the mapping
- If an execution filter for the mapping is defined, e.g. `temperature > 95`, it must evaluate to `true`

The following screen offers two ways to define subscriptions:

- **Subscriptions static**: Select specific individual devices using a tree or table view. Subscriptions are
  created explicitly for the chosen devices and are not updated automatically when devices are added or removed
  from a group.
- **Subscriptions dynamic (by group)**: Specify device groups. When a group is added, subscriptions for all
  assigned devices are created. When a device is added to or removed from a chosen group, the subscription is
  automatically created or deleted. The filter applies to child assets and child devices.
- **Subscriptions dynamic (by device type)**: Specify a list of device types. When a new device of one of the
  configured types is registered, a subscription is automatically created for it.

:::info
Use **dynamic subscriptions** with device types when you have many devices of the same type. This automatically
creates subscriptions for new devices as they're added to the system, reducing manual configuration overhead.
:::

![Substitution mapping outbound](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Mapping_Subscription_Outbound.png "Screenshot of subscribed devices to receive messages for outbound.")

#### Resync existing devices into a type subscription

Dynamic subscriptions by device type only subscribe devices that are **created after** the type was added to the
list. Devices of that type which already existed beforehand are not picked up automatically. Use **Resync
existing devices** to backfill subscriptions for those pre-existing devices.

:::info
When a device type is removed from the dynamic subscription list, subscriptions that were already created for
that type are **not** deleted.
:::

Clicking **Resync existing devices** next to the device type list opens a dialog listing all configured device
types together with the time of their last successful resync. Selecting **Resync existing devices** for a given
type triggers a background job that rescans the full inventory for devices of that type and creates the missing
subscriptions. This can take a while on large inventories, so the request is submitted asynchronously — progress
and the outcome (e.g. number of devices subscribed) can be tracked via Service Events.

![Resync existing devices into a type subscription](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Mapping_Subscription_Outbound_Resync.png "Screenshot of the dialog used to resync existing devices into a device type subscription.")

#### Inventory Filter and Execution Filter

Two optional filters let you narrow which outbound messages are processed without writing any transformation
code:

##### Inventory Filter

Evaluated once against the device's managed object properties when the mapping is first triggered for that
device. If the expression returns `false`, the mapping is skipped entirely for that device. The expression is a
JSONata expression evaluated against the device's inventory data.

```javascript
// Only process pressure sensors
type == "c8y_PressureSensor"

// Only process devices with a hardware serial number fragment
$exists(c8y_Hardware.serialNumber)

// Only process active devices of a specific model
c8y_IsDevice = true and c8y_Hardware.model = "SmartSensor v2"
```

##### Execution Filter

Evaluated at runtime against each outgoing message payload. If the expression returns `false`, the message is
silently dropped and not forwarded to the broker. The expression is a JSONata expression evaluated against the
Cumulocity source object (measurement, event, alarm, or managed object) that triggered the mapping.

```javascript
// Only forward high-temperature measurements
c8y_TemperatureMeasurement.T.value > 95

// Only forward critical alarms
severity = "CRITICAL"

// Only forward events of a specific type
type = "c8y_Uplink"
```

:::info
Both filters use JSONata expression syntax. The **Inventory Filter** operates on the device managed object, while
the **Execution Filter** operates on the triggering payload (measurement, event, alarm). Use filters to reduce
unnecessary broker traffic without modifying your transformation logic.
:::

### Transformation types {#transformation-types}

The Dynamic Mapper offers four powerful ways to transform your data between external formats and Cumulocity IoT.
Each transformation type provides different levels of control and flexibility, allowing you to choose the
approach that best fits your use case:

:::info
**Choosing a Transformation Type:** Select JSONata for simple mappings, Smart Functions when you need complete
control over the transformation logic, JavaScript computations, or when working with array payloads, and Java
Extensions when you need enterprise-grade type safety, performance, and access to the full Java ecosystem.
:::

**Quick-reference decision guide:**

| If you need… | Use this type | Notes |
|---|:---:|---|
| Simple field-to-field mapping | **JSONata** | Declarative, no JavaScript knowledge required |
| Complex expressions, conditional logic, math | **JSONata** | JSONata supports functions, predicates, and aggregations natively |
| Familiar imperative JavaScript syntax per-field | **Smart Function** | JavaScript Substitutions have been removed in release 6.3; Smart Functions offer a superset of capabilities |
| Full control over the output payload | **Smart Function** | You build the entire target object; all substitution types are bypassed |
| Array inputs producing multiple C8Y objects | **Smart Function** | Return an array from the function; each element becomes a separate C8Y request |
| Binary / Protobuf / unknown-format payloads | **Smart Function + Any Payload** | Payload is passed as a Base64 string; decode and parse it in your function |
| Java type safety, existing Java libraries, server-side execution | **Java Extension** | Packaged as a JAR uploaded to the Cumulocity tenant; compiled JVM performance |

#### Defining a substitution using JSONata {#jsonata-substitution}

JSONata is a powerful query and transformation language for JSON that supports path expressions, predicates,
functions, and aggregations. Use JSONata when you need declarative, expression-based transformations for
straightforward field mappings without requiring programming knowledge.

**[Learn more about defining substitutions using JSONata →](/c8y-pkg-dynamic-mapper/introduction/jsonata)**

#### Defining the payload transformation using a Smart Function (JavaScript) {#javascript-smart-function}

Smart Functions provide complete programmatic control over the entire transformation logic using JavaScript,
allowing you to define the full payload structure rather than just substitutions. Use Smart Functions when you
need maximum flexibility with access to device inventory data, complex business logic, multiple outputs from a
single input message, or state management across messages.

**[Learn more about Smart Functions and metadata usage →](/c8y-pkg-dynamic-mapper/introduction/smartfunction)**

#### Removed: Substitution as JavaScript (release 6.3) {#javascript-substitution}

:::important Removed in release 6.3
**TransformationType.SUBSTITUTION_AS_CODE has been removed.** Existing mappings of this type are no longer
executed. The only permitted operations are **Export** and **Delete**. Editing, testing, activating, or
duplicating such mappings is not supported.
:::

Migrate to **Smart Function (JavaScript)**:

1. Export the affected mapping via the **Export** action in the mapping grid.
2. Create a new mapping with transformation type **Smart Function (JavaScript)**. Instead of returning a
   `SubstitutionResult`, the function returns a fully-built Cumulocity object directly. See
   [Smart Functions →](/c8y-pkg-dynamic-mapper/introduction/smartfunction) for the API and code templates.
3. Use the built-in **Test** feature to verify the migrated mapping.
4. Activate the new mapping and delete the original deprecated mapping.

**[View migration guide for Substitution as JavaScript →](/c8y-pkg-dynamic-mapper/introduction/smartfunction)**

#### Defining the payload transformation using Java Extensions {#java-extension}

Java Extensions provide enterprise-grade transformation capabilities by allowing you to write custom
transformation logic in Java. This approach offers type safety, superior performance, and full access to the Java
ecosystem including third-party libraries and Cumulocity Java SDK. Use Java Extensions when you need to handle
complex data transformations, implement sophisticated business logic, require strong type checking, or need to
integrate with existing Java-based systems.

**[Learn more about Java Extensions →](/c8y-pkg-dynamic-mapper/introduction/javaextension)**

### Using state in Java Extension and Smart Function {#flow-state}

Both **Java Extensions** and **Smart Functions** can maintain persistent state across multiple message invocations
for the same mapping. This enables stateful processing patterns such as message counters, running averages,
min/max tracking, deduplication, and rate limiting — without requiring an external database.

State is scoped per **tenant** and **mapping**. All devices processed by the same mapping share the same state
bucket. To separate state per device, prefix your keys with the client ID (see examples below).

:::info Info — Lifetime & TTL
State is held **in-memory** and does not survive a microservice restart.

The **Minutes lifetime flow state** setting (in Service Configuration → Caching) controls automatic expiry. The
timer measures the time since the *last write* to that tenant's state store. If no state has been written for
longer than the configured duration, the **entire state** for that tenant is cleared on the next scheduled
cleanup cycle (which runs every minute).

For example, with a value of `1440` (the default, equal to 24 hours): if no mapping has written any state for 24
hours, all stored state for that tenant is discarded. This prevents unbounded memory growth when mappings are
inactive.

Set to `0` to disable automatic expiry entirely. Flow state can also be cleared immediately via the **Clear flow
state** button on the Caching configuration page.
:::

#### State in Smart Functions (JavaScript)

Smart Functions use `context.getState(key)` and `context.setState(key, value)`. GraalVM automatically converts
between JavaScript and Java types, so you can pass native JavaScript values directly. `getState` returns `null`
(not `undefined`) when a key is absent.

**Global counter (shared across all devices):**

```javascript
function onMessage(message, context) {
  // Increment a counter shared by all devices using this mapping
  const count = context.getState("messageCount");
  context.setState("messageCount", count !== null ? count + 1 : 1);

  // ... rest of processing
}
```

**Per-device counter (prefix key with clientId):**

```javascript
function onMessage(message, context) {
  const clientId = context.getClientId();
  const key = clientId + ".messageCount";

  const count = context.getState(key);
  context.setState(key, count !== null ? count + 1 : 1);

  // ... rest of processing
}
```

#### State in Java Extensions

Java Extensions use `context.getNativeState(key)` and `context.setNativeState(key, value)`, which work with plain
Java objects (no GraalVM dependency). Setting a key to `null` removes it. State is persisted to the
`FlowStateStore` automatically after each invocation.

**Global counter (shared across all devices):**

```java
@Override
public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
    // Increment a counter shared by all devices using this mapping
    Integer count = (Integer) context.getNativeState("messageCount");
    context.setNativeState("messageCount", count == null ? 1 : count + 1);

    // ... rest of processing
}
```

**Per-device counter (prefix key with clientId):**

```java
@Override
public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
    String clientId = context.getClientId();
    String key = clientId + ".messageCount";

    Integer count = (Integer) context.getNativeState(key);
    context.setNativeState(key, count == null ? 1 : count + 1);

    // ... rest of processing
}
```

**Available state methods in Java Extensions:**

| Method | Description |
|---|---|
| `setNativeState(key, value)` | Store any Java object. Passing `null` as value removes the key. |
| `getNativeState(key)` | Retrieve a stored value, or `null` if not present. |
| `getNativeStateAll()` | Return an unmodifiable view of the entire state map for this mapping. |

### Code Templates for transformation types involving JavaScript {#code-templates}

The Dynamic Mapper provides predefined code templates for each transformation type and direction. These templates
serve as starting points for building your mappings and can be customized according to your requirements.

:::info
**Using Code Templates:** Code templates are available in the mapping editor when creating or editing mappings.
You can view, customize, and test these templates before applying them to your mappings.
:::

A **Code Template** is the JavaScript starting point that is copied verbatim into every new mapping that uses a
JavaScript-based transformation type. Each mapping maintains its own independent copy — modifying a template after
a mapping has been created does *not* affect existing mappings.

| Template Type | Direction | Purpose | Editable? |
|---|---|---|---|
| `INBOUND_SMART_FUNCTION` | Inbound | Starting template for inbound Smart Function mappings | Yes (duplicate system template to customise) |
| `OUTBOUND_SMART_FUNCTION` | Outbound | Starting template for outbound Smart Function mappings | Yes (duplicate system template to customise) |
| `SHARED` | Both | Evaluated before every Smart Function execution — define helper functions and constants here that are available as globals in all Smart Functions without any import statement | Yes |
| `SYSTEM` | — | Read-only canonical defaults maintained by the mapper. Use **Duplicate** to create a customisable copy. The *Init system code templates* action restores all system templates to factory defaults (your custom templates are not affected). | No (read-only) |
