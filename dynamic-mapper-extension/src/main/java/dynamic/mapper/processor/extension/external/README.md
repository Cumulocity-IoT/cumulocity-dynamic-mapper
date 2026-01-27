# External Extension Packages

This directory contains external Java extensions that can be dynamically loaded to extend the mapping processor functionality.

## Package Structure

```
external/
├── inbound/          # Extensions for device → Cumulocity processing
│   ├── ProcessorExtensionCustomEvent.java
│   ├── ProcessorExtensionCustomAlarm.java
│   ├── ProcessorExtensionCustomMeasurement.java
│   └── CustomEventOuter.java (Protobuf definition)
│
└── outbound/         # Extensions for Cumulocity → device processing
    └── ProcessorExtensionAlarmToCustomJson.java
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
2. Implement `ProcessorExtensionSource<byte[]>`
3. Override `extractFromSource(ProcessingContext<byte[]> context)`
4. Extract device data and add substitutions
5. Register in `extension-external.properties`

```java
package dynamic.mapper.processor.extension.external.inbound;

public class ProcessorExtensionMyDevice implements ProcessorExtensionSource<byte[]> {
    @Override
    public void extractFromSource(ProcessingContext<byte[]> context) {
        // Parse device payload
        byte[] payload = context.getPayload();

        // Extract data and add substitutions
        context.addSubstitution("temperature", "25.5", TYPE.NUMBER, RepairStrategy.DEFAULT, false);
    }
}
```

### Outbound Extension

1. Create class in `external/outbound/` package
2. Implement `ProcessorExtensionSource<byte[]>` (for extraction phase)
3. Override `extractFromSource(ProcessingContext<byte[]> context)`
4. Parse Cumulocity data and create device-specific substitutions
5. Register in `extension-external.properties`

```java
package dynamic.mapper.processor.extension.external.outbound;

public class ProcessorExtensionMyDeviceCommand implements ProcessorExtensionSource<byte[]> {
    @Override
    public void extractFromSource(ProcessingContext<byte[]> context) {
        // Parse Cumulocity operation/alarm/event
        Map<String, Object> c8yData = parsePayload(context.getPayload());

        // Convert to device format
        String deviceCommand = convertToDeviceFormat(c8yData);

        // Add substitutions for template processing
        context.addSubstitution("deviceCommand", deviceCommand, TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
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
    new String(context.getPayload(), "UTF-8"));
```

### Parsing Protobuf
```java
MyProtoMessage message = MyProtoMessage.parseFrom(context.getPayload());
```

### Extracting Device Identity
```java
context.addSubstitution(
    context.getMapping().getGenericDeviceIdentifier(),
    deviceId,
    TYPE.TEXTUAL,
    RepairStrategy.DEFAULT,
    false
);
```

### Creating Timestamps
```java
context.addSubstitution("time",
    new DateTime(timestamp).toString(),
    TYPE.TEXTUAL,
    RepairStrategy.DEFAULT,
    false
);
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
