---
title: SparkPlug B
---

### SparkPlug B {#sparkplugb}

The Dynamic Mapper provides native, first-class support for the
[Eclipse Sparkplug B](https://sparkplug.eclipse.org/) protocol — a standardized MQTT-based messaging specification
designed for Industrial IoT (IIoT) environments. Sparkplug B defines a strict topic namespace, payload encoding
(protobuf), and a birth/data/death message lifecycle that the mapper understands and handles automatically.

The **SparkPlug B** payload type is supported for both **inbound** and **outbound** mappings and requires a
**MQTT** connector (MQTT Service support only partially due to missing retain messages). Inbound decodes NBIRTH /
NDATA / DBIRTH / DDATA / NCMD / DCMD protobuf payloads automatically. Outbound serializes the metric object
returned by your Smart Function to SparkPlug B protobuf binary before publishing NCMD or DCMD messages to the
broker.

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

##### How birth fragments are stored

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

##### Supported metric types

The `type` field in each metric entry is case-insensitive and supports: `Int8`, `Int16`, `Int32`, `Int64`,
`UInt8`, `UInt16`, `UInt32`, `UInt64`, `Float`, `Double`, `Boolean`, `String`, `DateTime`, `Text`, `UUID`, `Bytes`.

