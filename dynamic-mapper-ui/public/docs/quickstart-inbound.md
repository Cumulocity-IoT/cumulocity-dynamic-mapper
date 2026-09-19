---
title: Quickstart inbound
---

### Quickstart: your first inbound mapping {#quickstart-inbound}

By the end of this page a temperature reading published to an MQTT topic will show up as a measurement on a device
in Cumulocity. It takes about 15 minutes.

This walkthrough stays on the default path: the **Cumulocity MQTT Service** connector, a **JSON** payload and a
**Smart Function**. Those are the defaults, so you do not have to make a single choice along the way. Everything
else the mapper can do is covered in the guides, once you have this working.

**Before you start** you need the *Dynamic Mapper Admin* role (to create connectors) and the *Dynamic Mapper User*
role (to create mappings). If you are missing either, the Home page warns you when it loads. See
[Managing permissions](/c8y-pkg-dynamic-mapper/introduction/access-control).

#### Step 1 — Create the connector

The Cumulocity MQTT Service is a broker that already runs in your tenant, so there is nothing to install and no
credentials to look up: the connector fills its connection settings in from the microservice automatically.

1. Go to [**Configuration → Connectors**](/c8y-pkg-dynamic-mapper/node3/connectorConfiguration) and click
   **Add connector**.
2. Choose **Cumulocity MQTT Service** as the connector type.
3. Give it a name, for example `quickstart`, and save.

The connector appears in the table and turns **connected** after a few seconds. If it does not, open its
connection logs from the same table — they name the underlying error. See the
[Connector reference](/c8y-pkg-dynamic-mapper/introduction/connectors) for the details of every field.

#### Step 2 — Start a mapping

Go to [**Mapping → Inbound**](/c8y-pkg-dynamic-mapper/node1/mappings/inbound) and click **Add Mapping**.

Leave **Expert Mode** switched off. The dialog then gives you the defaults — payload format **JSON** and a
**Smart Function**, which is a small JavaScript function that turns each message into Cumulocity objects. There is
nothing to choose here, so continue.

:::important
The payload format and the transformation type cannot be changed once the mapping exists. Changing your mind means
deleting the mapping and creating a new one.
:::

#### Step 3 — Point it at a connector and a topic

The wizard has five steps. The first two are configuration:

1. **Connector** — select the `quickstart` connector you just created.
2. **General settings** — fill in:
   - **Mapping Name**: `Quickstart temperature`
   - **Target API**: `Measurement`
   - **Mapping Topic**: `quickstart/+`
   - **Mapping Topic Sample**: `quickstart/device_01`
   - Switch on **Create non-existing devices**, so the mapper creates the device for you the first time a message
     arrives.

The `+` in the mapping topic is a single-level wildcard, so this one mapping serves every device publishing under
`quickstart/`. The *sample* topic is the concrete example the later steps run against.

#### Step 4 — Give it a source payload

In the **Select templates** step, enter the payload your device sends as the source template:

```json
{
  "temperature": 21.5,
  "unit": "C"
}
```

This is what your Smart Function receives as `msg.payload`, and what the **Test** step in Step 6 runs against.

#### Step 5 — Write the Smart Function

The **Transformation** step opens a JavaScript editor already filled with the default code template. Replace its
body with the function below:

```javascript
function onMessage(msg, context) {
    var payload = msg.payload;

    // Topic is "quickstart/device_01" — the second segment identifies the device.
    var externalId = context.getConfig().topic.split("/")[1];
    console.log("External device id:" + externalId);

    return [{
        cumulocityType: "measurement",
        action: "create",
        payload: {
            "time": msg.time,
            "type": "c8y_TemperatureMeasurement",
            "c8y_TemperatureMeasurement": {
                "T": {
                    "unit": payload["unit"],
                    "value": payload["temperature"]
                }
            }
        },
        externalSource: [{ "type": "c8y_Serial", "externalId": externalId }],
        contextData: {
            "deviceName": "Temperature-Sensor-01",
            "deviceType": "c8y_TemperatureSensor"
        }
    }];
}
export { onMessage };
```

Four things are worth noticing, because most inbound Smart Functions do them:

- It **returns an array**. One message can produce several Cumulocity objects; here it produces one.
- `cumulocityType` and `action` say *what* to create — a measurement, in this case.
- `externalSource` says *which device* the data belongs to. Without it the mapper cannot route the measurement.
- `contextData.deviceName` and `contextData.deviceType` describe the **device to create**. This is where the
  **Create non-existing devices** switch from Step 3 pays off: the first message from an unknown external ID
  creates the device, and without these it would appear in Device Management under its external ID with no type at
  all. Here it is created as `Temperature-Sensor-01` of type `c8y_TemperatureSensor`.

:::info
Both apply **only at creation**. Once the device exists, later messages neither rename it nor change its type —
edit it in Device Management instead.

The **type** is worth setting deliberately: it is what device lists, smart rules and dashboards filter on, so
devices created without one are awkward to work with later. A type is a free-form string; the `c8y_` prefix is
only a convention.

Note also that the example hard-codes one name while the mapping topic `quickstart/+` serves *every* device under
`quickstart/`. That is fine for a single test device, but in practice derive the name from the message — for
example `"deviceName": "Sensor " + externalId`. The type is usually the same for every device a mapping creates,
so a constant is normally right there.

`contextData` shapes the created device further still, through `deviceFragments` and `deviceGroups`. See
[Smart Functions](/c8y-pkg-dynamic-mapper/introduction/smartfunction) for the full list.
:::

#### Step 6 — Test, save, activate

The last step runs your function against the source template from Step 4 and shows the Cumulocity object it
produces. Expand **Console output** underneath to see anything your function printed with `console.log()` — add a
line like `console.log("payload:", payload)` if the result is not what you expected. See
[Testing a Smart Function](/c8y-pkg-dynamic-mapper/introduction/define-mapping#testing-smart-function).

Check that the measurement looks right, then **save** the mapping and **activate** it from the mapping table — a
mapping that is not activated is never executed.

#### Step 7 — Send a message

Publish to `quickstart/device_01` through the Cumulocity MQTT Service:

```json
{ "temperature": 23.7, "unit": "C" }
```

In **Device Management** a device named **Temperature-Sensor-01**, of type `c8y_TemperatureSensor`, now exists —
carrying a temperature measurement. It was created by the first message, with the name and type coming from
`contextData`, and the external ID `device_01` — taken from the topic — recorded under its **Identity** tab. That
external ID, not the name, is what links every later message to this device.

:::info
Nothing arrived? Open [Monitoring](/c8y-pkg-dynamic-mapper/introduction/monitoring) — it counts the messages each
mapping received and the errors it raised, which tells you immediately whether the message reached the mapping or
failed inside it. [Troubleshooting](/c8y-pkg-dynamic-mapper/introduction/troubleshooting) covers the usual causes.
:::

#### What to read next

- [Quickstart outbound](/c8y-pkg-dynamic-mapper/introduction/quickstart-outbound) — the other direction: send a
  command from Cumulocity back to the device, reusing the connector and device you just created.
- [Core concepts](/c8y-pkg-dynamic-mapper/introduction/concepts) — the vocabulary behind what you just built.
- [Smart Functions](/c8y-pkg-dynamic-mapper/introduction/smartfunction) — the full API: device lookups, state,
  multiple outputs, binary payloads.
- [Code templates](/c8y-pkg-dynamic-mapper/introduction/code-templates) — ready-made starting points instead of
  writing from scratch.
- [Message Explorer](/c8y-pkg-dynamic-mapper/introduction/message-explorer) — if you do not know what your real
  devices send, capture a live message and build the mapping from it.
