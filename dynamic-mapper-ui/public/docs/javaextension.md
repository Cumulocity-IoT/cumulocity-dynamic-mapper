---
title: Defining the payload transformation using Java Extensions
---

Java Extensions let you write custom transformation logic in Java. This approach offers compile-time type checking
and access to third-party Java libraries and the Cumulocity Java SDK.

**When to use Java Extensions:** Choose Java Extensions when you need:
- Compile-time type checking
- Compiled execution for complex transformations
- Binary format support for Protobuf, Avro, MessagePack, and other non-JSON protocols
- Reuse of existing Java code
- Access to third-party Java libraries and the Cumulocity Java SDK
- Advanced debugging capabilities with standard Java tools

:::caution
Java Extensions must be deployed as plugins to the Dynamic Mapper microservice before they can be used. Once
installed, they appear in the Processor Extension configuration and become available as templates in the mapping
stepper.
:::

##### End-to-end overview

```mermaid
flowchart LR
    subgraph build["Build"]
        direction TB
        s1["1. Implement the interface<br/>ProcessorExtensionInbound"]
        s2["2. Register the class in<br/>extension-external.yaml"]
        s3["3. Package classes + yaml<br/>into a jar archive"]
        s1 --> s2 --> s3
    end

    subgraph deploy["Deploy"]
        direction TB
        s4["4. Upload the jar<br/>Configuration &rarr;<br/>Processor extension"]
    end

    subgraph run["Run"]
        direction TB
        s5["5. Create a mapping with<br/>transformation type<br/>Extension Java"]
        s6["6. Mapping is active<br/>onMessage() runs on every<br/>matching message"]
        s5 --> s6
    end

    s3 --> s4 --> s5

    style build fill:#f3f3f3,stroke:#8a8a8a
    style deploy fill:#fff7d6,stroke:#FFBE00
    style run fill:#e8f4e8,stroke:#119D11
```

Each step in detail:

1. **Implement** `ProcessorExtensionInbound<byte[]>` for broker-to-Cumulocity, or
   `ProcessorExtensionOutbound<O>` for Cumulocity-to-broker.
2. **Register** the class in `extension-external.yaml` with its `eventName`, `className`, `description`,
   `version` and an optional default parameter map.
3. **Package** the yaml together with the compiled classes into a single jar.
4. **Upload** it under **Configuration → Processor extension → Add extension**. The microservice loads the jar
   dynamically, per tenant, no redeployment.
5. **Create a mapping** with transformation type **Extension Java**, select the extension and its `eventName`, and
   optionally override the parameter map for that mapping.
6. At runtime `onMessage(...)` receives every matching message. `context.getConfigAsMap()` exposes the tenant,
   topic and parameter map, and the `CumulocityObject` results your method returns are sent to Cumulocity.

##### Selecting Java Extensions in the mapping stepper

When creating a mapping, you can select from installed Java Extensions that define the transformation logic. In the
**Select templates** step, choose the **Java Extension**, then pick one of its events:

![Java Extension in Mapping Stepper](../../../resources/image/Dynamic_Mapper_Mapping_Stepper_Java_Extension.png "The Select templates step of the mapping stepper for a Java Extension mapping. The Java Extension selector shows the installed extension, and the Events list below it shows the processors it provides, each with a short description. No target template is shown because the extension code produces the target payload.")

##### Uploading a Java Extension

A Java Extension is packaged as a single `*.jar` and uploaded through the UI, no redeployment of the
microservice is required. Build the jar first, the **End-to-end overview** above shows where this step sits, then:

1. Go to **Configuration → Processor extension**. The page lists every extension currently known to the service,
   each card showing whether it is **External** (uploaded by you) or **Internal** (shipped with the service), and
   how many processors it contributed under **Loaded**.

![Processor extension page](../../../resources/image/Dynamic_Mapper_Configuration_Processor_Extension.png "The Processor extension page listing the external and internal extensions with the number of loaded processors on each card.")

2. Choose **Add extension**. In the **Add Processor Extension** dialog, drop your jar onto the upload area or
   click it to pick the file, then confirm with **Done**.

![Add processor extension](../../../resources/image/Dynamic_Mapper_Configuration_Processor_Extension_Upload.png "The Add Processor Extension dialog with the area for uploading a *.jar file.")

3. The service loads the jar and registers every `ProcessorExtensionInbound` / `ProcessorExtensionOutbound`
   implementation declared in its `extension-external.yaml`. Use **Reload** if the card does not yet show your
   extension, loading happens asynchronously, and the banner reports progress while it runs.

The jar must contain an `extension-external.yaml` naming each processor and its implementation class. An
extension whose descriptor is missing or malformed uploads successfully but shows **Not Loaded** on its card, with the
reason below it. Check the descriptor before looking anywhere else. **Reload** replaces all extensions in one step, so
mappings keep running with the previous version until the new one is ready.

You cannot delete an extension while a mapping uses it. The delete fails and names the mappings to change or delete
first. Built-in extensions cannot be deleted.

Once loaded, the extension's processors become selectable in the mapping wizard as described above, and the jar
survives microservice restarts, it is stored in the tenant, not on the container's filesystem.

##### Managing installed Java Extensions

To manage and view installed Java Extensions, navigate to the Processor Extension configuration page. This page
displays all deployed extensions with their properties, implementation details, and supported message types:

![Processor Extension Configuration](../../../resources/image/Dynamic_Mapper_Configuration_Processor_Extension_Plugin_Installed.png "Screenshot showing the Processor Extension configuration page with installed plugins. Each extension displays its name (e.g., CustomEvent, MeasurementToCustomJson), implementation class path, message type, direction (Outbound/Inbound), and active status. The interface allows you to view extension properties and verify that plugins are correctly installed and operational.")

A Java Extension implements one of two interfaces, depending on the direction it serves:

| Direction | Interface | Receives | Returns |
|---|---|---|---|
| **Inbound** (broker → Cumulocity) | `ProcessorExtensionInbound<byte[]>` | the raw broker payload as bytes | `CumulocityObject[]` |
| **Outbound** (Cumulocity → broker) | `ProcessorExtensionOutbound<O>` | the Cumulocity object, **already parsed** | `DeviceMessage[]` |

Both are implemented the same way: a single `onMessage(message, context)` that returns an array. Both are
packaged, registered and uploaded identically. The direction is derived from the interface you implement; you do
not declare it anywhere.

An empty or `null` result means "nothing to do". The mapper ignores the message, logs it at info level, and does not
count it as an error. Throw an exception (or, for outbound, a `ProcessingException`) when a message is invalid and
should count as a failure.

The mapper creates one instance of your class and reuses it for every message, so do not keep per-message data in
instance fields unless access is thread-safe. The class needs a public no-argument constructor. By default, external
extension classes must be in the package `dynamic.mapper.processor.extension.external` (or a sub-package), and external
extensions can be disabled for the whole service by an administrator.

###### Inbound: broker to Cumulocity

```java
public class ProcessorExtensionSmartInbound01 implements ProcessorExtensionInbound<byte[]> {

    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
        try {
            // Parse JSON payload
            String jsonString = new String(message.getPayload(), "UTF-8");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) Json.parseJson(jsonString);

            log.info("{} - Processing smart inbound message, messageId: {}",
                    context.getTenant(), payload.get("messageId"));

            // Get clientId from context first, fall back to payload
            String clientId = context.getClientId();
            if (clientId == null) {
                clientId = (String) payload.get("clientId");
            }

            // Extract data
            @SuppressWarnings("unchecked")
            Map<String, Object> sensorData = (Map<String, Object>) payload.get("sensorData");
            Number tempVal = (Number) sensorData.get("temp_val");

            log.debug("{} - Creating temperature measurement: {} C for device: {}",
                    context.getTenant(), tempVal, clientId);

            // Build measurement using builder pattern
            // Note: deviceName and deviceType are needed for implicit device creation
            return new CumulocityObject[] {
                CumulocityObject.measurement()
                    .type("c8y_TemperatureMeasurement")
                    .time(new DateTime().toString())
                    .fragment("c8y_Steam", "Temperature", tempVal.doubleValue(), "C")
                    .externalId(clientId, "c8y_Serial")
                    .deviceName(clientId)           // Use clientId as device name
                    .deviceType("c8y_TemperatureSensor")  // Device type for implicit creation
                    .build()
            };

        } catch (Exception e) {
            String errorMsg = "Failed to process inbound message: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            return new CumulocityObject[0];
        }
    }
}
```

Register the extension in **extension-external.yaml**:

```yaml
extensions:
  # Smart Function Equivalents - Inbound
  - eventName: TemperatureMeasurement
    className: dynamic.mapper.processor.extension.external.inbound.ProcessorExtensionSmartInbound01
    description: Temperature measurement processor demonstrating smart function pattern
    version: "2.0"
```

Package the configuration file **extension-external.yaml** and the compiled extension class into a `*.jar`, then
upload it as described in **Uploading a Java Extension** above.

###### Outbound: Cumulocity to broker

An outbound extension implements `ProcessorExtensionOutbound<O>` and returns `DeviceMessage[]`, one entry per
message to publish. Return an empty array to publish nothing.

```java
public class ProcessorExtensionSmartOutbound01 implements ProcessorExtensionOutbound<Object> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public DeviceMessage[] onMessage(Message<Object> message, JavaExtensionContext context)
            throws ProcessingException {
        try {
            // Outbound payloads arrive already parsed, cast, do not deserialize.
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) message.getPayload();

            log.info("{} - Payload raw: {}", context.getTenant(), payload);

            // The Cumulocity object carries the device under "source"
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) payload.getOrDefault("source", new HashMap<>());
            String sourceId = (String) source.get("id");

            @SuppressWarnings("unchecked")
            Map<String, Object> measurement = (Map<String, Object>) payload.get("c8y_TemperatureMeasurement");
            @SuppressWarnings("unchecked")
            Map<String, Object> series = (Map<String, Object>) measurement.get("T");
            Number value = (Number) series.get("value");

            // Build whatever shape the device expects
            Map<String, Object> devicePayload = new HashMap<>();
            devicePayload.put("time", new DateTime().toString());
            devicePayload.put("c8y_Steam", Map.of("Temperature", Map.of("unit", "C", "value", value)));

            String jsonPayload = objectMapper.writeValueAsString(devicePayload);

            return new DeviceMessage[] {
                DeviceMessage.forTopic("measurements/" + sourceId)
                    .payload(jsonPayload)
                    .build()
            };

        } catch (Exception e) {
            String errorMsg = "Failed to process outbound message: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            return new DeviceMessage[0];   // nothing is published
        }
    }
}
```

The payload of a `DeviceMessage` is not limited to JSON. With the payload type **Any Payload** and the transformation
type **Java Extension**, an outbound extension can send a raw device command or a binary message:

```java
// Raw text, sent exactly as given: no quotes, no JSON
DeviceMessage.forTopic("command/" + context.getExternalId())
    .payload("AT+GTUPD=gv300,0,,,http://example.com/fw.bin,,,$")
    .build();

// Binary: pass the bytes, they are sent unchanged
DeviceMessage.forTopic("firmware/device").payload(protobufBytes).build();

// Binary from Base64 text, with an explicit content type
DeviceMessage.forTopic("firmware/device")
    .payload("AAEC/w==").payloadEncoding(DeviceMessage.ENCODING_BASE64)
    .contentType("application/x-protobuf")
    .build();
```

A `Map` or object is sent as JSON, a string as text, and a `byte[]` as bytes. A string that starts with `{`, and one
that is a valid JSON array or scalar, keep their earlier behavior as JSON. Use `DeviceMessage.ENCODING_TEXT` to send
such a string as text. Text and binary go to the broker only: they are never routed to a Cumulocity API.

Register it exactly as an inbound extension, the `className` is what tells the mapper it is outbound:

```yaml
extensions:
  # Outbound Extensions (Cumulocity → Device)
  - eventName: MeasurementToCustomJson
    className: dynamic.mapper.processor.extension.external.outbound.ProcessorExtensionSmartOutbound01
    description: Measurement to custom JSON converter with flexible formatting
    version: "2.0"
```

**Two things that differ from inbound and catch people out:**

:::important
The outbound payload is **already parsed**. `message.getPayload()` returns the Cumulocity object as a
`Map<String, Object>`, not bytes, casting is correct, and calling `new String(...)` or a JSON parser on it fails.
Inbound is the opposite: there you do get raw `byte[]` and parse it yourself.
:::

The **publish topic** is yours to choose per message. Either take the one configured on the mapping with
`context.getMapping().getPublishTopic()`, or build it from the payload as above, `"measurements/" + sourceId`
sends each device's data to its own topic from a single mapping.

Beyond `payload(...)`, the `DeviceMessage` builder offers:

| Builder method | Use |
|---|---|
| `topic(...)` / `DeviceMessage.forTopic(...)` | The topic to publish to. |
| `retain(true\|false)` | MQTT retain flag. |
| `transportField(key, value)` | One transport-specific field, e.g. `transportField("qos", "1")`, or the Kafka record key / Pulsar message key, see [Accessing the broker message key](#broker-message-key). |
| `transportFields(map)` | Several at once. |
| `clientId(...)` / `transportId(...)` | Override the publishing client or transport. |
| `time(...)` | Message timestamp. |

Returning several `DeviceMessage` entries publishes several messages from one Cumulocity object, useful for
fan-out, or for protocols that need a header message before the payload.

##### Extension parameters

Java Extensions can receive runtime configuration through a **parameter map**. This allows the same extension class
to behave differently depending on the mapping it is used in, without recompiling or redeploying the extension.

Parameters are accessible inside the extension via `context.getConfigAsMap()`, which returns a map containing both
runtime context (tenant, clientId, topic, mapping metadata) and the user-supplied `parameter` map nested under the
key **"parameter"**.

###### 1. Define default parameters in extension-external.yaml

Default parameter values can be declared directly in the extension registration YAML. These defaults apply whenever
no per-mapping override is provided:

```yaml
extensions:
  - eventName: SparkplugBMeasurement
    className: dynamic.mapper.processor.extension.external.inbound.ProcessorExtensionSparkplugBMeasurement
    description: Sparkplug B processor with configurable fragment and unit
    version: "1.0"
    parameter:
      units:
        unit1: V
        unit2: A
      fragment: Energy
```

###### 2. Override parameters per mapping in the UI

When creating or editing a mapping in the mapping stepper, a **Parameter** YAML field is shown for mappings that use
a Java Extension. Values entered here override or extend the defaults from the YAML registration file and are
stored as part of the mapping. This lets different mappings using the same extension class operate with entirely
different configuration:

```yaml
units:
  unit1: W
fragment: Power
```

###### 3. Read parameters inside the extension

Call `context.getConfigAsMap()` to retrieve the full config. The user-supplied parameter map is nested under the
**"parameter"** key:

```java
@Override
public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
    Map<String, Object> config = context.getConfigAsMap();

    // The "parameter" key contains the per-mapping (or YAML default) parameters
    @SuppressWarnings("unchecked")
    Map<String, Object> parameter = (Map<String, Object>) config.get("parameter");

    String fragment = "SparkplugMetrics"; // default fallback
    String unit     = "°C";              // default fallback

    if (parameter != null) {
        if (parameter.get("fragment") instanceof String f) {
            fragment = f;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> units = (Map<String, Object>) parameter.get("units");
        if (units != null && units.get("unit1") instanceof String u) {
            unit = u;
        }
    }

    // Use fragment and unit to build CumulocityObject...
}
```

##### getConfigAsMap(): full content

In addition to `parameter`, the map returned by `getConfigAsMap()` also contains:
- **tenant**: current tenant identifier
- **clientId**: MQTT/connector client ID
- **topic**: topic of the message
- **externalId**: the resolved external ID of the source device (outbound, when the mapping uses an external ID)
- **mappingId**, **mappingName**, **targetAPI**, **debug**: mapping metadata

`context.getTenant()`, `context.getMapping()`, and `context.getExternalId()` return the same values directly.
`context.addWarning(text)` records a warning that appears in the test result and the microservice log. Extension state
(`setNativeState`) is kept between messages of the same mapping, see [Flow state](/c8y-pkg-dynamic-mapper/introduction/flow-state).

##### Collecting several messages into one {#collect}

Some devices send one message per value, with the same timestamp. To create one measurement from them, the extension
has to hold the first messages back until the group is complete. Do not build this with `setNativeState` and
`getNativeState`: of messages that run at the same time, none sees the others. Use `context.collectNative`, which adds an
item to a named group and checks whether the group is complete in one atomic step. It is the Java counterpart of
[`context.collect`](/c8y-pkg-dynamic-mapper/introduction/flow-state#collect) in Smart Functions.

The example below merges a temperature message and a humidity message of the same timestamp into one measurement:

```java
@Override
public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
    Map<String, Object> payload = parse(message);   // { "clientId", "time", "sensor", "value" }
    String clientId = payload.get("clientId").toString();
    String sensor = payload.get("sensor").toString();   // "temperature" or "humidity"

    // One group per device and timestamp. Complete when both sensors are present.
    // A group whose partner never arrives is dropped after 60 seconds.
    CollectOptions options = CollectOptions.keys("temperature", "humidity").withMaxAgeSeconds(60);
    Map<String, Object> group = context.collectNative(
            clientId + "|" + payload.get("time"), sensor, payload.get("value"), options);
    if (group == null) {
        return new CumulocityObject[0];   // the other message has not arrived yet
    }

    return new CumulocityObject[] {
        CumulocityObject.measurement()
            .type("c8y_TemperatureMeasurement")
            .time(payload.get("time"))
            .fragment("c8y_Temperature", "T", ((Number) group.get("temperature")).doubleValue(), "C")
            .fragment("c8y_Humidity", "H", ((Number) group.get("humidity")).doubleValue(), "%")
            .externalId(clientId, "c8y_Serial")
            .build()
    };
}
```

`collectNative(groupKey, itemKey, value, options)` returns `null` until the group is complete. The call that completes
it receives the group as a map of item key to value, and the group is removed. A repeated item key replaces the earlier
value and does not count twice. Build the options with:

| Options | The group is complete when |
|---|---|
| `CollectOptions.count(n)` | it holds `n` different item keys. `collectNative(groupKey, itemKey, value, n)` is a shorthand. |
| `CollectOptions.keys("a", "b")` | all of these item keys are present |
| `CollectOptions.onLast()` | this message is the last one |
| `.withMaxAgeSeconds(s)` | not a condition: incomplete groups older than `s` seconds, counted from their first item, are dropped. Without it, an incomplete group stays until it is complete. |

To send a partial group, for example when a newer timestamp arrives, list the open groups with
`context.openGroupsNative(prefix)` and remove one with `context.takeNative(groupKey)`. `takeNative` returns the group as
it is, complete or not.

Things to know:

- Groups are kept per mapping, in memory only. A restart of the microservice drops them.
- Unlike `setNativeState`, an item is stored at once, not after the message was delivered. If the message that
  receives a group fails (the extension throws or the result is not delivered), the group is put back, so a redelivery
  completes it again.
- The same limits apply as for Smart Functions: about 8 MB per mapping for state and groups together, and 1,000 open groups.
- In a test run nothing is collected. The methods return `null` or an empty list and add a warning.
- The `Value`-based `context.collect`, `take` and `openGroups` of Smart Functions throw `UnsupportedOperationException` in Java extensions.

The extension examples `CollectMeasurement` (inbound) and `BatchMeasurements` (outbound, three measurements per device
as one JSON array) show both directions.

##### Accessing the broker message key {#broker-message-key}

`message.getTransportFields()` returns the transport-specific fields of the received broker message. For Kafka,
`key` holds the record key, the key, not a header. For Pulsar, `key` holds the message key (absent when the message has none):

```java
@Override
public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
    String deviceId = message.getTransportFields().get("key");   // e.g. "863859042393327"
    // falls back to null when the message carries no key
    ...
}
```

The key is delivered **out-of-band, it is not part of the payload**, so it never appears in the mapping's source
template. A Kafka record key or Pulsar message key is frequently the device identifier, which makes it a useful source for the external
ID. The map is never `null`; it is empty when the transport carries no such fields.

This mirrors `msg.transportFields["key"]` in Smart Functions and `_CONTEXT_DATA_.key` in JSONata mappings.

The same map also holds the broker metadata of the message, with names that show where it comes from: `header.<name>` (Kafka and AMQP 0-9-1 headers), `property.<name>` (Pulsar properties and MQTT 5 user properties), `attribute.<name>` (Google Pub/Sub attributes), and `routingKey` and `exchange` (AMQP 0-9-1). See the table in [Reading the broker message key and metadata](smartfunction.md) for the full list. A header can be missing on individual messages, so `get` can return `null`:

```java
String tenantHeader = message.getTransportFields().get("header.tenant");
```

For **outbound** mappings, set the field on the returned `DeviceMessage` to define the key to publish with:

```java
return new DeviceMessage[] {
    DeviceMessage.forTopic(context.getMapping().getPublishTopic())
        .payload(customJson)
        .transportField("key", externalId)   // sets the Kafka record key / Pulsar message key
        .build()
};
```

##### Key benefits of Java Extensions

- **Type safety:** Compile-time type checking catches many errors in your IDE before deployment.
- **Performance:** Java code runs compiled on the JVM. Smart Functions run in an interpreter with a CPU
  budget (`maxCPUTimeMS`).
- **Binary format support:** Parse and process binary data formats such as Protocol Buffers (Protobuf), Avro,
  MessagePack, and other non-JSON formats. Java Extensions receive the raw bytes. JSONata mappings cannot read
  these formats, and Smart Functions must decode them in JavaScript.
- **Java ecosystem:** Use Java libraries, frameworks, and the Cumulocity Java SDK, for example for JSON
  processing, data validation, and cryptography.
- **Reusability:** Package and deploy transformation logic as reusable plugins across multiple mappings. Once
  developed, a Java extension can be used by multiple tenants and mapping configurations.
- **Debugging:** Use standard Java tools and IDEs during development, such as breakpoints in IntelliJ IDEA or Eclipse.

:::important
**Development Requirements:** To develop Java Extensions, you need:
- Java Development Kit (JDK) 25 or higher
- Maven or Gradle for building the extension
- Access to the Dynamic Mapper extension API and dependencies
:::
