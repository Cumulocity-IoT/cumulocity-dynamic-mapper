# External Extension Packages

This directory contains external Java extensions that can be dynamically loaded to extend the mapping processor functionality.

## Package Structure

```
external/
├── inbound/          # Extensions for device → Cumulocity processing
│   ├── ProcessorExtensionCustomEvent.java
│   ├── ProcessorExtensionCustomAlarm.java
│   ├── ProcessorExtensionCustomMeasurement.java
│   ├── ProcessorExtensionSparkplugBMeasurement.java (Protobuf / SparkplugB)
│   ├── ProcessorExtensionSmartInbound01..06.java (SMART function examples)
│   └── CustomEventOuter.java (Protobuf definition)
│
└── outbound/         # Extensions for Cumulocity → device processing
    ├── ProcessorExtensionAlarmToCustomJson.java
    └── ProcessorExtensionSmartOutbound01..03.java (SMART function examples)
```

## Directory Purpose

### `inbound/` - Device to Cumulocity
Extensions in this package process incoming device data and convert it to Cumulocity format.

**Use cases:**
- Parse proprietary device payloads (Protobuf, custom binary, etc.)
- Extract device data and create measurements, events, alarms
- Handle custom device protocols

**Example:** `ProcessorExtensionCustomEvent.java`
- Parses Protobuf payload from device
- Extracts timestamp, text, event type, device ID
- Creates Cumulocity event via substitutions

### `outbound/` - Cumulocity to Device
Extensions in this package process Cumulocity data and convert it to device-specific format.

**Use cases:**
- Convert Cumulocity alarms/events to device format
- Transform commands to device-specific protocol
- Implement custom device communication formats

**Example:** `ProcessorExtensionAlarmToCustomJson.java`
- Receives Cumulocity alarm representation
- Converts to custom JSON format expected by device
- Maps severity levels and alarm types to device codes

## Extension Registration

Extensions are registered in `extension-external.properties`:

```properties
# Inbound Extensions (Device → Cumulocity)
CustomEvent=dynamic.mapper.processor.extension.external.inbound.ProcessorExtensionCustomEvent
CustomMeasurement=dynamic.mapper.processor.extension.external.inbound.ProcessorExtensionCustomMeasurement
CustomAlarm=dynamic.mapper.processor.extension.external.inbound.ProcessorExtensionCustomAlarm

# Outbound Extensions (Cumulocity → Device)
AlarmToCustomJson=dynamic.mapper.processor.extension.external.outbound.ProcessorExtensionAlarmToCustomJson
```

## Package Validation

The framework validates that external extensions are in the allowed package:
- Configured in `application.properties`: `APP.externalExtensionsAllowedPackage=dynamic.mapper.processor.extension.external`
- Both `inbound` and `outbound` subpackages are allowed
- This is a security measure to prevent loading arbitrary code

## Creating New Extensions

### Inbound Extension

1. Create class in `external/inbound/` package
2. Implement `ProcessorExtensionInbound<byte[]>`
3. Override `onMessage(Message<byte[]> message, JavaExtensionContext context)`
4. Parse device payload, build `CumulocityObject[]` using the builder API, and return it
5. Register in `extension-external.properties`

```java
package dynamic.mapper.processor.extension.external.inbound;

import dynamic.mapper.processor.extension.ProcessorExtensionInbound;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.JavaExtensionContext;
import dynamic.mapper.processor.model.Message;

public class ProcessorExtensionMyDevice implements ProcessorExtensionInbound<byte[]> {
    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
        // Parse device payload
        byte[] payload = message.getPayload();
        String deviceId = "...";   // extracted from payload or topic

        return new CumulocityObject[] {
            CumulocityObject.measurement()
                .type("c8y_Temperature")
                .fragment("c8y_Temperature", "T", 25.5, "C")
                .externalId(deviceId, context.getMapping().getExternalIdType())
                .build()
        };
    }
}
```

### Outbound Extension

1. Create class in `external/outbound/` package
2. Implement `ProcessorExtensionOutbound<Object>`
3. Override `onMessage(Message<Object> message, JavaExtensionContext context)`
4. Parse Cumulocity payload, build `DeviceMessage[]` using the builder API, and return it
5. Register in `extension-external.properties`

```java
package dynamic.mapper.processor.extension.external.outbound;

import dynamic.mapper.processor.extension.ProcessorExtensionOutbound;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.JavaExtensionContext;
import dynamic.mapper.processor.model.Message;

public class ProcessorExtensionMyDeviceCommand implements ProcessorExtensionOutbound<Object> {
    @Override
    public DeviceMessage[] onMessage(Message<Object> message, JavaExtensionContext context) {
        // Parse Cumulocity operation/alarm/event
        String customJson = convertToDeviceFormat(message.getPayload());

        return new DeviceMessage[] {
            DeviceMessage.forTopic(context.getMapping().getPublishTopic())
                .payload(customJson)
                .retain(false)
                .build()
        };
    }
}
```

## Direction Specification

Extensions can optionally declare their intended direction in the `ExtensionEntry`:

- `Direction.INBOUND` - Only for inbound processing
- `Direction.OUTBOUND` - Only for outbound processing
- `Direction.UNSPECIFIED` - Can be used in both (default)

This is validated at runtime to prevent misuse.

## Best Practices

### 1. Package Organization
- Keep inbound and outbound extensions separate
- Use clear, descriptive class names
- Group related extensions together

### 2. Error Handling
- Always catch and log exceptions
- Throw `ProcessingException` for processing errors
- Provide clear error messages with context

### 3. Logging
- Use tenant ID in log messages: `log.info("{} - Message", tenant, ...)`
- Log at appropriate levels (DEBUG for details, INFO for major steps, WARN/ERROR for issues)
- Include payload information for debugging (if not sensitive)

### 4. Substitutions
- Use meaningful substitution keys
- Choose correct TYPE (TEXTUAL, NUMBER, BOOLEAN)
- Use RepairStrategy.DEFAULT unless specific handling needed
- Set expandArray to false unless handling arrays

### 5. Testing
- Test with actual device payloads
- Verify substitutions are created correctly
- Test error handling paths
- Validate output format matches device expectations

## Common Patterns

### Parsing JSON
```java
Map<String, Object> jsonData = (Map<String, Object>) Json.parseJson(
    new String(message.getPayload(), "UTF-8"));
```

### Parsing Protobuf
```java
MyProtoMessage proto = MyProtoMessage.parseFrom(message.getPayload());
```

### Extracting Device Identity via External ID
```java
CumulocityObject.measurement()
    .externalId(deviceId, context.getMapping().getExternalIdType())
    .build();
```

### Creating Timestamps
```java
CumulocityObject.measurement()
    .time(new DateTime(timestamp).toString())
    .build();
```

### Looking Up a Device in the Inventory
```java
ExternalId extId = new ExternalId(deviceId, "c8y_Serial");
Map<String, Object> device = context.getManagedObjectAsMap(extId);
if (device != null) {
    String deviceName = (String) device.get("name");
}
```

## Deployment

1. **Development**: Extensions in this directory are compiled with the mapper
2. **External JARs**: Extensions can be packaged as JAR and uploaded to Cumulocity
3. **Hot Reload**: Use the reload API endpoint to refresh extensions without restart

## Security Considerations

- Extensions run with full application privileges
- Only load trusted extension code
- Package validation prevents loading from arbitrary packages
- Review extension code before deployment
- Monitor extension execution for anomalies

## Troubleshooting

### Extension Not Found
- Check `extension-external.properties` registration
- Verify fully qualified class name is correct
- Ensure class is in `dynamic.mapper.processor.extension.external.*` package

### Package Validation Error
- Extension must be in `dynamic.mapper.processor.extension.external` package or subpackages
- Check package declaration matches file location

### ClassNotFoundException
- Verify all dependencies are available
- Check imports are correct
- Ensure extension is properly compiled

### Direction Mismatch Error
```
Extension has direction INBOUND but is being used in OUTBOUND processing
```
- Check extension direction setting
- Verify mapping direction matches extension capability
- Use Direction.UNSPECIFIED for bidirectional extensions

## Examples in This Repository

### Inbound Examples
1. **ProcessorExtensionCustomEvent** - Protobuf event parsing
2. **ProcessorExtensionCustomMeasurement** - JSON measurement parsing
3. **ProcessorExtensionCustomAlarm** - Complex alarm processing with device lookup

### Outbound Examples
1. **ProcessorExtensionAlarmToCustomJson** - Alarm to custom JSON conversion

## Further Reading

- Main documentation: `dynamic-mapper-service/README.md`
- Extension interfaces: `dynamic-mapper-service/../processor/extension/`
- Configuration: `application.properties`
- Extension management: `ExtensionManager.java`
