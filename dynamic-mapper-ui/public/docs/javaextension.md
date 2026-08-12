---
title: Defining the payload transformation using Java Extensions
---

Java Extensions provide enterprise-grade transformation capabilities by allowing you to write custom transformation
logic in Java. This approach offers type safety, superior performance, and full access to the Java ecosystem
including third-party libraries and the Cumulocity Java SDK.

:::info
**When to use Java Extensions:** Choose Java Extensions when you need:
- Enterprise-grade type safety with compile-time checking
- Optimal performance for complex transformations
- Binary format support for Protobuf, Avro, MessagePack, and other non-JSON protocols
- Integration with existing Java-based enterprise systems
- Access to the full Java ecosystem and Cumulocity Java SDK
- Advanced debugging capabilities with standard Java tools
:::

:::caution
Java Extensions must be deployed as plugins to the Dynamic Mapper microservice before they can be used. Once
installed, they appear in the Processor Extension configuration and become available as templates in the mapping
stepper.
:::

##### Selecting Java Extensions in the Mapping Stepper

When creating a mapping, you can select from installed Java Extensions that define the transformation logic. The
mapping stepper displays all available extensions along with their associated templates:

![Java Extension in Mapping Stepper](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Mapping_Stepper_Substitution_ProcessorExtension.png "Screenshot showing the mapping stepper with Java Extension templates. The dropdown displays available extensions for payload parsing, including various custom extensions like CustomEvent, CustomMeasurement, and MeasurementWithImplicitDevice. Each extension provides pre-configured templates for both source and target payloads.")

##### Managing Installed Java Extensions

To manage and view installed Java Extensions, navigate to the Processor Extension configuration page. This page
displays all deployed extensions with their properties, implementation details, and supported message types:

![Processor Extension Configuration](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Configuration_ProcessorExtension_Plugin_Installed.png "Screenshot showing the Processor Extension configuration page with installed plugins. Each extension displays its name (e.g., CustomEvent, MeasurementToCustomJson), implementation class path, message type, direction (Outbound/Inbound), and active status. The interface allows you to view extension properties and verify that plugins are correctly installed and operational.")

The signature and structure of a **Java Extension** has the form:

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

Package the configuration file **extension-external.yaml** and the compiled extension class into a zip archive, then
upload it as described in **Managing Installed Java Extensions**.

##### Extension Parameters

Java Extensions can receive runtime configuration through a **parameter map**. This allows the same extension class
to behave differently depending on the mapping it is used in — without recompiling or redeploying the extension.

Parameters are accessible inside the extension via `context.getConfigAsMap()`, which returns a map containing both
runtime context (tenant, clientId, topic, mapping metadata) and the user-supplied `parameter` map nested under the
key **"parameter"**.

###### 1. Define default parameters in extension-external.yaml

Default parameter values can be declared directly in the extension registration YAML. These defaults apply whenever
no per-mapping override is provided:

```yaml
extensions:
  - eventName: SparkplugBWithConfigMeasurement
    className: dynamic.mapper.processor.extension.external.inbound.ProcessorExtensionSparkplugBWithConfigMeasurement
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

:::info getConfigAsMap() — full content
In addition to `parameter`, the map returned by `getConfigAsMap()` also contains:
- **tenant** — current tenant identifier
- **clientId** — MQTT/connector client ID
- **topic** — incoming message topic
- **mappingId**, **mappingName**, **targetAPI**, **debug** — mapping metadata
:::

##### Key Benefits of Java Extensions

- **Type Safety:** Compile-time type checking prevents runtime errors and improves code reliability. Your IDE will
  catch errors before deployment, reducing debugging time and production issues.
- **Performance:** Native Java execution provides optimal performance for complex transformations. Unlike
  interpreted JavaScript, Java extensions benefit from JVM optimizations and efficient memory management.
- **Binary Format Support:** Parse and process binary data formats such as Protocol Buffers (Protobuf), Avro,
  MessagePack, and other non-JSON formats. Java Extensions can handle binary payloads that cannot be processed by
  JSONata or JavaScript transformations, making them ideal for IoT devices using efficient binary protocols.
- **Java Ecosystem:** Full access to Java libraries, frameworks, and the Cumulocity Java SDK. Leverage existing
  libraries for JSON processing, data validation, cryptography, and more.
- **Reusability:** Package and deploy transformation logic as reusable plugins across multiple mappings. Once
  developed, a Java extension can be used by multiple tenants and mapping configurations.
- **Advanced Debugging:** Use standard Java debugging tools and IDEs for development. Set breakpoints, inspect
  variables, and step through code using IntelliJ IDEA, Eclipse, or any Java debugger.

:::important
**Development Requirements:** To develop Java Extensions, you need:
- Java Development Kit (JDK) 21 or higher
- Maven or Gradle for building the extension
- Access to the Dynamic Mapper extension API and dependencies
:::
