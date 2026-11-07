---
title: Custom Routing
---

Smart Functions can inspect and override routing metadata at runtime, both when **receiving** a message from a
broker (source template) and when **publishing** a result back to a broker or to a tenant microservice (target
template). Two key metadata properties govern this:

- **`topic`**: the broker topic associated with the message (readable in source templates; overridable in target
  templates).
- **`targetPath`**: a tenant-microservice path used instead of a broker topic when the result must be forwarded to
  a Cumulocity tenant microservice.

### Using metadata in source templates (inbound) {#custom-routing-source}

When the mapper receives a message from a broker, an inbound Smart Function can read the topic of that message from
the configuration:

| Expression | What it returns |
|---|---|
| `context.getConfig()["topic"]` | The full *live* topic string of the received message, for example `"testDevice/sensor-berlin-01/data"`. |

Smart Functions do not receive the `_TOPIC_LEVEL_` array that JSONata mappings get. Split the live topic yourself
and read the segment that carries the device identity:

```javascript
function onMessage(msg, context) {
  var payload = msg.getPayload();

  // config["topic"] = "testDevice/sensor-berlin-01/data"  ->  segments[1] = "sensor-berlin-01"
  var segments = (context.getConfig()["topic"] || "").split("/");
  var externalId = segments.length > 1 ? segments[1] : payload["externalId"];

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
 * The resolved externalId is available as context.getConfig().externalId
 * (requires Use external id and an External Id type on the mapping).
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

:::important Important: `targetPath` restriction
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

#### Inbound example: measurement and microservice forwarding {#custom-routing-inbound}

An inbound Smart Function can return an array to both create a Cumulocity measurement *and* forward the raw
reading to a tenant microservice in a single mapping:

```javascript
function onMessage(msg, context) {
  var payload = msg.getPayload();
  // Read device identity from the live topic segment
  var segments = (context.getConfig()["topic"] || "").split("/");
  var externalId = segments.length > 1 ? segments[1] : payload["externalId"];

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

#### Outbound example: operation forwarding via `targetPath` {#custom-routing-outbound}

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

##### Limitations

- Only tenant-local microservices can be called via `targetPath` (path must start with `/service/`).
- No device identity resolution is performed for `cumulocityType: "custom"` objects. The `externalSource` property
  is ignored.
- Custom routing works for both **inbound** and **outbound** Smart Functions.
- The `topic` override (dynamic publish topic) applies only to **outbound** Smart Functions and requires an active
  broker connection.
