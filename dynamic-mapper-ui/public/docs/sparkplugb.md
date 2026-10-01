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

Separately, a connector can also act as a Sparkplug B **Primary Host Application** — see
[Sparkplug Host Mode](#sparkplug-host-mode) at the bottom of this page. That's an optional,
connector-level role for announcing your own online/offline state to the network; you don't need
it just to consume/map Sparkplug B data as described below.

#### Topic Structure

Sparkplug B topics follow the fixed format: `spBv1.0/[Group ID]/[Message Type]/[Edge Node ID]/[Device ID]`

All birth/alias/active-state bookkeeping — for both the Edge Node **and** every Device attached
to it — is stored as fragments on the **Edge Node's** managed object. Sparkplug Devices do not
get their own managed object unless your Smart Function explicitly creates one (see
[Birth Message Handling](#birth-message-handling) below).

| Message Type | Topic Levels | Default External ID Type | Fragment Stored (always on the Edge Node MO) | Description |
|---|:---:|:---:|---|---|
| `NBIRTH` | 4 | `c8y_Serial` | `sparkPlugB_NBIRTH` | Edge Node birth — metric definitions published when the node comes online. The mapper stores the alias→metric-definition map on the Edge Node managed object so that subsequent NDATA messages can resolve aliases. The Edge Node external ID is `[Group ID]_[Edge Node ID]` (external ID **type** defaults to `c8y_Serial`, overridable per-mapping via `externalIdType`). |
| `NDATA` | 4 | — | — | Edge Node data — metric values published periodically. Aliases are resolved using the `sparkPlugB_NBIRTH` fragment stored on the Edge Node MO. The decoded payload is passed to your Smart Function. |
| `DBIRTH` | 5 | `c8y_Serial` | `sparkPlugB_DBIRTH_[deviceId]` | Device birth — metric definitions for a device attached to an Edge Node. **The Edge Node MO must already exist** (from a prior NBIRTH) — unlike NBIRTH, DBIRTH has no `createNonExistingDevice` auto-create fallback; if the Edge Node isn't found, the birth fragment can't be stored and an error is logged. No separate managed object is created for the Sparkplug Device itself unless your Smart Function does so explicitly. |
| `DDATA` | 5 | — | — | Device data — metric values for a specific device. Aliases are resolved using the `sparkPlugB_DBIRTH_[deviceId]` fragment stored on the Edge Node MO. The decoded payload is passed to your Smart Function. |
| `NDEATH` / `DDEATH` | 4 / 5 | — | `sparkPlugB_isActive` / `sparkPlugB_isActive_[deviceId]` | Node/Device death — signals that the node or device has gone offline. `sparkPlugB_isActive` (NDEATH) or `sparkPlugB_isActive_[deviceId]` (DDEATH) is set to `false`; the same fragments are set `true` on NBIRTH/NDATA or DBIRTH/DDATA respectively. This bookkeeping happens automatically regardless of what your Smart Function returns. Death messages are passed to the Smart Function without alias resolution. |
| `NCMD` / `DCMD` | 4 / 5 | — | — | Node/Device command — command messages sent to a node or device. **Inbound:** aliases are resolved like NDATA/DDATA respectively and passed to the Smart Function. **Outbound:** the metric object returned by the Smart Function is serialized to protobuf binary by `SparkPlugBSerializer` before publishing. Use `context.getConfig().aliasMap` to include numeric aliases in the outbound metrics — see [Smart Function Example — Outbound NCMD](#smart-function-example-outbound-ncmd). Outbound operations must target the **Edge Node's** managed object (not a per-device one), since that's where the alias/active-state fragments this needs actually live. |

#### How the Mapper Processes SparkPlug B Messages

##### Inbound

1. **Deserialization** — The binary protobuf payload is decoded automatically by the mapper's
   `SparkPlugBDeserializer`, using the wire-format message schema published by
   [Eclipse Tahu](https://github.com/eclipse-tahu/tahu) (`SparkplugBProto`). The metric/datatype/
   alias interpretation itself — resolving `dataType`, extracting the correct value per type,
   resolving aliases against the birth map — is the mapper's own logic, not delegated to Tahu's
   higher-level decoder. Either way, no manual decoding is needed in your Smart Function.
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

The object passed to your Smart Function via `msg.payload` (or `msg.getPayload()`) has the
following shape:

```
{
  "messageType": "NDATA",        // NBIRTH | NDATA | DBIRTH | DDATA | NDEATH | DDEATH | NCMD | DCMD
  "groupId":     "factory-01",   // Sparkplug Group ID
  "edgeNodeId":  "plc-01",       // Edge Node ID (always present)
  "deviceId":    "sensor-a",     // Device ID (present for D* messages only)
  "timestamp":   1713700000000,  // UTC milliseconds — the Sparkplug Payload-level timestamp.
                                  // Some publishers set this once and never advance it on
                                  // later messages; prefer each metric's own "timestamp"
                                  // (below) when you need an accurate per-message time.
  "seq":         42,             // Sequence number, when present
  "metrics": [
    {
      "name":      "Temperature",  // resolved from alias (NDATA/DDATA) or original (NBIRTH/DBIRTH)
      "alias":     12,             // present only if the metric was published with an alias
      "dataType":  "Float",        // spec name: Int8|Int16|Int32|Int64|UInt8|UInt16|UInt32|UInt64|
                                    //   Float|Double|Boolean|String|DateTime|Text|UUID|DataSet|
                                    //   Bytes|File|Template
      "value":     23.5,           // scalar value, already typed (number/boolean/string).
                                    // Bytes-type metrics are Base64-encoded strings, not a raw
                                    // byte array — decode with atob()/Java.type('java.util.Base64')
                                    // if you need the original bytes.
      "timestamp": 1713700000000,  // this metric's own timestamp — prefer this over the
                                    // Payload-level "timestamp" above
      "isHistorical": false,       // present only if set on the wire
      "isTransient":  false        // present only if set on the wire
    }
  ]
}
```

There is no `sparkPlugB_NBIRTH`/`sparkPlugB_DBIRTH_[deviceId]` entry attached to this object —
the birth/alias map is stored on the Edge Node's managed object (see the topic table above),
not passed inline in the message payload. Read it with
`context.getManagedObject(...)` if your Smart Function needs it directly.

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

**NBIRTH** and **DBIRTH** are handled differently — DBIRTH has no auto-create fallback and always
targets the Edge Node, never a separate per-device managed object.

**NBIRTH** (creates/updates the Edge Node MO):

1. **Smart Function returns a `managedObject` object** — the mapper upserts the MO (creates or
   updates it) and immediately stores the birth fragment on it. This is the recommended path for
   the *first boot* of a node.
2. **Smart Function returns nothing (or a non-`managedObject` object)** — the mapper derives the
   external ID directly from the topic (type `c8y_Serial` by default, or the mapping's configured
   `externalIdType`; value `[Group ID]_[Edge Node ID]`) and looks up the pre-existing MO in
   inventory. If found, the birth fragment is stored on it.
3. **MO still not found** — the mapper checks the mapping's `createNonExistingDevice` flag. If
   enabled, a minimal MO is auto-created with that external ID and the birth fragment is stored on
   it. Otherwise an error is logged and the birth fragment cannot be stored — causing all
   subsequent `NDATA` alias lookups for this node to fail.

**DBIRTH** (stores a fragment on the *existing* Edge Node MO — no separate device MO, no
auto-create):

1. The mapper resolves the **Edge Node's** external ID from the topic (`[Group ID]_[Edge Node
   ID]`, same as NBIRTH) and looks it up in inventory.
2. **If the Edge Node MO doesn't exist yet, the birth fragment is dropped and an error is
   logged** — "Ensure the NBIRTH message has been processed first." There is no
   `createNonExistingDevice` fallback here, unlike NBIRTH: send/process an NBIRTH for the node
   before any DBIRTH for its devices.
3. Whatever your Smart Function returns for DBIRTH doesn't affect this storage step at all — the
   fragment (key `sparkPlugB_DBIRTH_[deviceId]`) always lands on the Edge Node MO found in step 1.
   If you also want the Sparkplug Device to be visible as its own entry in Cumulocity's inventory,
   return a `managedObject` object for it explicitly (with its own external ID, e.g.
   `[Group ID]_[Edge Node ID]_[Device ID]`) — that's a separate, optional MO purely for
   visibility/addressing, unrelated to alias resolution.

:::important Important — first boot
On the **first ever boot** of an Edge Node (i.e. before its MO exists in inventory), choose one of
two approaches:

- Enable **createNonExistingDevice** on the mapping — the mapper automatically creates a minimal
  MO using the external ID derived from the topic. This is the simplest option and requires no
  changes to the Smart Function.
- Return a `CumulocityObject` with `cumulocityType: "managedObject"` from the Smart Function — use
  this when you need a custom device name, type, or additional fragments on the managed object.

For subsequent NBIRTH reboots the fallback lookup handles fragment storage automatically even if
the Smart Function returns nothing. DBIRTH never auto-creates anything — its Edge Node must
already exist by the time a DBIRTH for one of its devices arrives.
:::

#### Smart Function Example — Inbound

The following example handles all Sparkplug B message types and maps NDATA temperature metrics to Cumulocity
measurements:

```javascript
function onMessage(msg, context) {
  var payload  = msg.payload;
  var msgType  = payload.messageType;  // e.g. "NBIRTH", "NDATA", "DBIRTH", "DDATA"
  var groupId  = payload.groupId;
  var nodeId   = payload.edgeNodeId;
  var deviceId = payload.deviceId;     // undefined for node-level messages

  // External IDs follow the Sparkplug B namespace to ensure global uniqueness:
  //   Edge Node: [Group ID]_[Edge Node ID]
  //   Device:    [Group ID]_[Edge Node ID]_[Device ID]
  var nodeExtId   = groupId + '_' + nodeId;
  var deviceExtId = deviceId ? groupId + '_' + nodeId + '_' + deviceId : undefined;

  // ── Birth messages: create / update a managed object ────────────────────────
  // For NBIRTH this is what lets the mapper store the alias map on the Edge Node MO
  // (see "Birth Message Handling" above). For DBIRTH the alias map is stored on the
  // Edge Node MO automatically either way — this call is only to also give the
  // Sparkplug Device its own visible entry in Cumulocity's inventory.
  if (msgType === 'NBIRTH' || msgType === 'DBIRTH') {
    var extId = msgType === 'NBIRTH' ? nodeExtId : deviceExtId;
    return [{
      cumulocityType: 'managedObject',
      externalSource: [{ externalId: extId, type: 'c8y_Serial' }],
      payload: {
        name: extId,
        type: 'c8y_Serial',
        c8y_IsDevice: {}
      }
    }];
  }

  // ── NDATA / DDATA: map metrics to measurements ───────────────────────────────
  if (msgType === 'NDATA' || msgType === 'DDATA') {
    var extId   = msgType === 'DDATA' ? deviceExtId : nodeExtId;
    var results = [];
    (payload.metrics || []).forEach(function(metric) {
      if (metric.name === 'Temperature') {
        // Prefer the metric's own timestamp over the Payload-level one — some
        // publishers never advance the latter across messages (see note above).
        var time = metric.timestamp ? new Date(metric.timestamp).toISOString() : msg.time;
        results.push({
          cumulocityType: 'measurement',
          externalSource: [{ externalId: extId, type: 'c8y_Serial' }],
          payload: {
            type: 'c8y_Temperature',
            time: time,
            'c8y_Temperature': { T: { value: metric.value, unit: '°C' } }
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
Edge Node's managed object during outbound enrichment: it prefers the `sparkPlugB_NBIRTH`
fragment, falling back to a merge of every `sparkPlugB_DBIRTH_[deviceId]` fragment present if no
NBIRTH fragment exists. Including the numeric alias in each outbound metric lets the edge node
match it without a full name lookup, reducing payload size. The `metric()` helper below adds the
alias only when one is available. Since this is loaded by resolving the C8Y operation's target
device, that target **must be the Edge Node's** managed object (not a separate per-device one) —
see the topic table above.

`context.getConfig().isActive` reflects whether the **Edge Node** is currently online (`true`
after NBIRTH/NDATA, `false` after NDEATH) — it does not tell you about a specific Device. For
per-device status when building a DCMD, use `context.getConfig().deviceActiveMap` instead: a
`{ deviceId → boolean }` map derived from every `sparkPlugB_isActive_[deviceId]` fragment on the
Edge Node MO. Both default to `true` when no status has been recorded yet (a node/device is
assumed active until an explicit DEATH message says otherwise).

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

#### Sparkplug Host Mode

Separately from mapping-level inbound/outbound decoding above, an **MQTT connector** can act as a
Sparkplug B **Primary Host Application** — the role a SCADA/monitoring system plays in the
Sparkplug B spec, announcing its own online/offline state to every Edge Node on the network. This
is unrelated to whether you have any SparkPlug B mappings configured; it's a connector-level
setting.

Enable it on the connector's configuration:

- **Is Sparkplug Host** — turns the behavior on for this connector.
- **Sparkplug Host ID** — your Host Application's identifier. If left blank, it falls back to the
  tenant ID. Must not contain `/`, `+`, or `#` (it becomes part of an MQTT topic), and must be
  unique if you have more than one Sparkplug Host on the same broker.

On connect, the connector:

1. Subscribes to `spBv1.0/#` — every Sparkplug B message on the broker, needed to track all Edge
   Nodes' state.
2. Publishes a **Birth Certificate** (retained, QoS 1) to `spBv1.0/STATE/<hostId>` announcing
   itself online, and republishes it periodically for brokers that don't reliably honor `retain`.

On disconnect, it publishes a **Death Certificate** to the same topic first. Despite the name,
this has nothing to do with X.509/TLS certificates — "Birth/Death Certificate" is Sparkplug B
protocol terminology for these online/offline state messages.

:::important Don't enable this just to consume data
If you only want to receive and map Sparkplug B traffic from edge nodes/devices into Cumulocity
(the common case, and everything described earlier on this page), you do **not** need Sparkplug
Host Mode — a plain MQTT connector with your `SPARKPLUGB` mappings deployed is enough. Enable Host
Mode only if you specifically need this connector to announce itself as the network's Primary Host
Application.
:::

