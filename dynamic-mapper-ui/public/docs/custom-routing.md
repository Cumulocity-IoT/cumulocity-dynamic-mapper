---
title: Custom Routing
---

Smart Functions can inspect and override routing metadata at runtime — both when **receiving** a message from a
broker (source template) and when **publishing** a result back to a broker or to a tenant microservice (target
template). Two key metadata properties govern this:

- **`topic`** — the broker topic associated with the message (readable in source templates; overridable in target
  templates).
- **`targetPath`** — a tenant-microservice path used instead of a broker topic when the result must be forwarded to
  a Cumulocity tenant microservice.

### Using metadata in source templates (inbound) {#custom-routing-source}

When the mapper receives a message from a broker, it injects routing metadata into the processing context that the
Smart Function can read:

| Expression | What it returns |
|---|---|
| `payload["_TOPIC_LEVEL_"]` | Array of segments from the *live* broker topic of the received message, e.g. `["testDevice","sensor-berlin-01","data"]`. |
| `context.getConfig()["topic"]` | The *configured* subscription topic pattern of the mapping (e.g. `"testDevice/+/data"`). Useful for finding which segment index holds the wildcard `+` and therefore the device identity. |
| `context.getTopic()` | The full live topic string of the received message. |

A common pattern is to locate the `+` wildcard in the configured topic pattern and read the corresponding live
segment as the device identity:

```javascript
function onMessage(msg, context) {
  var payload = msg.getPayload();
  var config = context.getConfig();

  // Find which segment of the subscription pattern is the wildcard.
  // config["topic"] = "testDevice/+/data"  →  topicSegments[1] = "+"
  var topicSegments = config["topic"] ? config["topic"].split("/") : [];
  var identityIndex = topicSegments.indexOf("+");

  // payload["_TOPIC_LEVEL_"] = ["testDevice", "sensor-berlin-01", "data"]
  var externalId = (identityIndex >= 0 && payload["_TOPIC_LEVEL_"])
    ? payload["_TOPIC_LEVEL_"][identityIndex]
    : payload["externalId"];

  return {
    cumulocityType: "measurement",
    action: "create",
    payload: {
      "time": new Date().toISOString(),
      "type": "c8y_TemperatureMeasurement",
      "c8y_Steam": { "Temperature": { "unit": "C", "value": payload["temperature"] } }
    },
    externalSource: [{ "type": "c8y_Serial", "externalId": externalId }]
  };
}
```

### Using metadata in target templates (outbound) {#custom-routing-target}

The object (or array of objects) returned by an outbound Smart Function can carry routing metadata that tells the
mapper *where* to deliver the result. Two routing strategies are available:

#### Dynamic broker topic via `topic` {#custom-routing-topic}

Return a `topic` property to override the static publish topic configured in the mapping. The mapper will publish
the `payload` to that topic on the connected broker.

```javascript
/**
 * Outbound: publish a Cumulocity measurement to a per-device MQTT topic.
 * The resolved externalId is available as context.getConfig().externalId.
 */
function onMessage(msg, context) {
  var payload = msg.getPayload();
  var externalId = context.getConfig().externalId;

  return {
    topic: `measurements/${externalId}`,   // override publish topic at runtime
    payload: {
      "time":  new Date().toISOString(),
      "c8y_Steam": {
        "Temperature": {
          "unit":  "C",
          "value": payload["c8y_TemperatureMeasurement"]["T"]["value"]
        }
      }
    }
  };
}
```

#### Tenant microservice routing via `targetPath` {#custom-routing-targetpath}

Return `cumulocityType: "custom"` together with a `targetPath` starting with `/service/` to forward the payload to
a tenant-local microservice instead of a broker topic. The mapper issues an HTTP request authenticated with the
same tenant credentials.

The `action` field controls the HTTP method: `"create"` → POST, `"update"` → PUT, `"patch"` → PATCH, `"delete"` →
DELETE.

:::important Important — `targetPath` restriction
`targetPath` must always begin with `/service/`. The mapper enforces this at runtime and throws a
`ProcessingException` for any other path. This ensures only microservices subscribed to the same Cumulocity tenant
can be called.
:::

#### Return object properties for custom routing {#custom-routing-properties}

| Property | Required | Description |
|---|:---:|---|
| `cumulocityType` | Yes | Must be `"custom"` to activate microservice routing. |
| `action` | Yes | `"create"` → POST, `"update"` → PUT, `"patch"` → PATCH, `"delete"` → DELETE. |
| `targetPath` | Yes | Path of the tenant microservice, e.g. `/service/my-processor/ingest`. Must start with `/service/`. |
| `payload` | Yes* | JSON body sent to the microservice. Can be omitted for `"delete"` if no body is needed. |

#### Inbound example — measurement + microservice forwarding {#custom-routing-inbound}

An inbound Smart Function can return an array to both create a Cumulocity measurement *and* forward the raw
reading to a tenant microservice in a single mapping:

```javascript
function onMessage(msg, context) {
  var payload = msg.getPayload();
  // Read device identity from the live topic segment
  var externalId = payload["_TOPIC_LEVEL_"]
    ? payload["_TOPIC_LEVEL_"][1]
    : payload["externalId"];

  return [
    // 1. Standard Cumulocity measurement
    {
      cumulocityType: "measurement",
      action: "create",
      payload: {
        "time": new Date().toISOString(),
        "type": "c8y_TemperatureMeasurement",
        "c8y_Steam": { "Temperature": { "unit": "C", "value": payload["temperature"] } }
      },
      externalSource: [{ "type": "c8y_Serial", "externalId": externalId }]
    },
    // 2. Forward to tenant microservice via HTTP POST (targetPath)
    {
      cumulocityType: "custom",
      action: "create",
      targetPath: "/service/my-processor/ingest",
      payload: {
        "deviceId":  externalId,
        "timestamp": new Date().toISOString(),
        "reading":   payload["temperature"]
      }
    }
  ];
}
```

#### Outbound example — operation forwarding via `targetPath` {#custom-routing-outbound}

An outbound Smart Function can forward a Cumulocity operation to a tenant microservice instead of publishing it to
a broker topic:

```javascript
function onMessage(msg, context) {
  var payload = msg.getPayload();
  var deviceId = payload["deviceId"] || (payload["source"] && payload["source"]["id"]);
  var command  = payload["c8y_Command"] && payload["c8y_Command"]["text"];

  return {
    cumulocityType: "custom",
    action: "create",          // HTTP POST
    targetPath: "/service/my-processor/execute",
    payload: {
      "deviceId":    deviceId,
      "command":     command,
      "operationId": payload["id"],
      "timestamp":   new Date().toISOString()
    }
  };
}
```

:::info Info — Limitations
- Only tenant-local microservices can be called via `targetPath` (path must start with `/service/`).
- No device identity resolution is performed for `cumulocityType: "custom"` objects — the `externalSource` property
  is ignored.
- Custom routing works for both **inbound** and **outbound** Smart Functions.
- The `topic` override (dynamic publish topic) applies only to **outbound** Smart Functions and requires an active
  broker connection.
:::
