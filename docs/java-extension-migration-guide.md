# Java Extension Migration Guide

## Overview

This guide helps you migrate Java extensions from the legacy side-effect based pattern to the new return-value based pattern (SMART function pattern). The new pattern provides cleaner code, better testability, and consistency with JavaScript SMART functions.

## Why Migrate?

### Benefits of the New Pattern

1. **30-40% Less Code**: No manual request building or direct API calls
2. **Better Testability**: Pure functions are easier to unit test
3. **Cleaner Code**: No side effects, clear inputs and outputs
4. **Type Safety**: Builder pattern catches errors at compile time
5. **Consistency**: Same pattern as JavaScript SMART functions
6. **Better Separation**: Framework handles API calls, you focus on business logic

### Backwards Compatibility

- **No breaking changes**: Existing extensions continue to work unchanged
- **Gradual migration**: Migrate at your own pace
- **Runtime detection**: Framework automatically detects which pattern you're using
- **No forced migration**: Both patterns will be supported indefinitely

## Quick Comparison

### Inbound Extension

#### Old Pattern (Deprecated)
```java
@Override
public void substituteInTargetAndSend(ProcessingContext<byte[]> context, C8YAgent c8yAgent) {
    // Parse payload
    Map<?, ?> json = Json.parseJson(new String(context.getPayload(), "UTF-8"));

    // Build request manually
    DocumentContext payloadTarget = JsonPath.parse(mapping.getTargetTemplate());
    // ... lots of manual substitution code ...

    // Create request
    DynamicMapperRequest request = DynamicMapperRequest.builder()
        .method(RequestMethod.POST)
        .api(API.ALARM)
        .request(payloadTarget.jsonString())
        .build();
    context.addRequest(request);

    // Call API directly
    c8yAgent.createMEAO(context, context.getRequests().size() - 1);
}
```

#### New Pattern (Recommended)
```java
@Override
public CumulocityObject[] onMessage(Message<byte[]> message, DataPreparationContext context) {
    // Parse payload
    Map<?, ?> json = Json.parseJson(new String(message.getPayload(), "UTF-8"));

    // Build and return - that's it!
    return new CumulocityObject[] {
        CumulocityObject.alarm()
            .type(json.get("type").toString())
            .severity(json.get("severity").toString())
            .text(json.get("text").toString())
            .externalId(json.get("deviceId").toString(),
                       context.getMapping().getExternalIdType())
            .build()
    };
}
```

### Outbound Extension

#### Old Pattern (Deprecated)
```java
@Override
public void extractAndPrepare(ProcessingContext<byte[]> context) throws ProcessingException {
    // Parse Cumulocity payload
    Map<?, ?> alarm = Json.parseJson(new String(context.getPayload(), "UTF-8"));

    // Transform to custom format
    Map<String, Object> customFormat = buildCustomFormat(alarm);
    String customJson = objectMapper.writeValueAsString(customFormat);

    // Create request manually
    DynamicMapperRequest request = DynamicMapperRequest.builder()
        .publishTopic(context.getResolvedPublishTopic())
        .request(customJson)
        .build();

    // Add to context (side effect)
    context.addRequest(request);
}
```

#### New Pattern (Recommended)
```java
@Override
public DeviceMessage[] onMessage(Message<byte[]> message, DataPreparationContext context) {
    // Parse Cumulocity payload
    Map<?, ?> alarm = Json.parseJson(new String(message.getPayload(), "UTF-8"));

    // Transform to custom format
    Map<String, Object> customFormat = buildCustomFormat(alarm);
    String customJson = objectMapper.writeValueAsString(customFormat);

    // Build and return - that's it!
    return new DeviceMessage[] {
        DeviceMessage.forTopic(context.getMapping().getPublishTopic())
            .payload(customJson)
            .retain(false)
            .build()
    };
}
```

## Step-by-Step Migration Guide

### Inbound Extension Migration

#### Step 1: Update Method Signature

**Old:**
```java
@Override
public void substituteInTargetAndSend(ProcessingContext<byte[]> context, C8YAgent c8yAgent)
```

**New:**
```java
@Override
public CumulocityObject[] onMessage(Message<byte[]> message, DataPreparationContext context)
```

**Changes:**
- Return type: `void` → `CumulocityObject[]`
- Parameter 1: `ProcessingContext<byte[]> context` → `Message<byte[]> message`
- Parameter 2: `C8YAgent c8yAgent` → `DataPreparationContext context`

#### Step 2: Update Payload Access

**Old:**
```java
byte[] payload = context.getPayload();
String topic = context.getTopic();
String clientId = context.getClientId();
```

**New:**
```java
byte[] payload = message.getPayload();
String topic = message.getTopic();
String clientId = message.getClientId();
```

#### Step 3: Update Context Access

**Old:**
```java
String tenant = context.getTenant();
Mapping mapping = context.getMapping();
Boolean testing = context.getTesting();
```

**New:**
```java
String tenant = context.getTenant();  // Same
Mapping mapping = context.getMapping();  // Same
Boolean testing = context.getTesting();  // Same
C8YAgent agent = context.getC8YAgent();  // Now available via context
```

#### Step 4: Replace Manual Request Building with Builders

**Old:**
```java
// Build JSON manually
DocumentContext payloadTarget = JsonPath.parse(mapping.getTargetTemplate());
SubstituteValue.substituteValueInPayload(substitute, payloadTarget, pathTarget);

// Create request
var request = DynamicMapperRequest.builder()
    .method(RequestMethod.POST)
    .api(API.ALARM)
    .externalIdType(mapping.getExternalIdType())
    .externalId(externalId)
    .request(payloadTarget.jsonString())
    .build();
context.addRequest(request);

// Call API
c8yAgent.createMEAO(context, requestIndex);
```

**New:**
```java
// Use builder - framework handles everything else
return new CumulocityObject[] {
    CumulocityObject.alarm()
        .type("c8y_TemperatureAlarm")
        .severity("CRITICAL")
        .text("Temperature exceeds threshold")
        .time(new DateTime().toString())
        .status("ACTIVE")
        .externalId(externalId, context.getMapping().getExternalIdType())
        .build()
};
```

#### Step 5: Implement Required extractFromSource Method

Since the interface still requires `extractFromSource` from `InboundExtension`, add this:

```java
@Override
public void extractFromSource(ProcessingContext<byte[]> context) throws ProcessingException {
    throw new UnsupportedOperationException(
        "This extension uses the new onMessage() pattern");
}
```

### Outbound Extension Migration

#### Step 1: Update Method Signature

**Old:**
```java
@Override
public void extractAndPrepare(ProcessingContext<byte[]> context) throws ProcessingException
```

**New:**
```java
@Override
public DeviceMessage[] onMessage(Message<byte[]> message, DataPreparationContext context)
```

#### Step 2: Update Payload and Context Access

Same as inbound - use `message.getPayload()` instead of `context.getPayload()`.

#### Step 3: Replace Manual Request Building with Builder

**Old:**
```java
// Create request manually
DynamicMapperRequest request = DynamicMapperRequest.builder()
    .publishTopic(context.getResolvedPublishTopic())
    .retain(false)
    .request(customJsonString)
    .build();

// Add to context (side effect)
context.addRequest(request);
```

**New:**
```java
// Use builder and return
return new DeviceMessage[] {
    DeviceMessage.forTopic(context.getMapping().getPublishTopic())
        .payload(customJsonString)
        .retain(false)
        .transportField("qos", "1")
        .build()
};
```

#### Step 4: Implement Required extractFromSource Method

```java
@Override
public void extractFromSource(ProcessingContext<byte[]> context) throws ProcessingException {
    throw new UnsupportedOperationException(
        "This extension uses the new onMessage() pattern");
}
```

## Common Patterns and Recipes

### Creating Multiple Objects

**Inbound - Create alarm and measurement:**
```java
@Override
public CumulocityObject[] onMessage(Message<byte[]> message, DataPreparationContext context) {
    Map<?, ?> json = Json.parseJson(new String(message.getPayload(), "UTF-8"));
    String externalId = json.get("deviceId").toString();
    String externalIdType = context.getMapping().getExternalIdType();

    return new CumulocityObject[] {
        // Create alarm
        CumulocityObject.alarm()
            .type("c8y_TemperatureAlarm")
            .severity("CRITICAL")
            .text("Temperature critical")
            .externalId(externalId, externalIdType)
            .build(),

        // Create measurement
        CumulocityObject.measurement()
            .type("c8y_TemperatureMeasurement")
            .time(new DateTime().toString())
            .fragment("c8y_Temperature", "T", 95.5, "C")
            .externalId(externalId, externalIdType)
            .build()
    };
}
```

**Outbound - Publish to multiple topics:**
```java
@Override
public DeviceMessage[] onMessage(Message<byte[]> message, DataPreparationContext context) {
    String customJson = buildCustomFormat(message.getPayload());

    return new DeviceMessage[] {
        // Device-specific topic
        DeviceMessage.forTopic("device/12345/alarms")
            .payload(customJson)
            .build(),

        // Broadcast topic
        DeviceMessage.forTopic("alarms/critical")
            .payload(customJson)
            .retain(true)
            .transportField("qos", "2")
            .build()
    };
}
```

### Using Device Metadata

For implicit device creation:

```java
return new CumulocityObject[] {
    CumulocityObject.alarm()
        .type("c8y_TemperatureAlarm")
        .severity("CRITICAL")
        .text("Temperature critical")
        .externalId(externalId, externalIdType)
        .deviceName("Temperature Sensor 1")  // Used for device creation
        .deviceType("c8y_TemperatureSensor") // Used for device creation
        .build()
};
```

### Using Context Data

```java
// Add warnings for debugging
if (context.getManagedObjectByDeviceId(externalId) == null) {
    context.addWarning("Device not found, will be created implicitly");
}

// Add logs for monitoring
context.addLog("Processing alarm for device: " + externalId);

// Access mapping configuration
String externalIdType = context.getMapping().getExternalIdType();
String publishTopic = context.getMapping().getPublishTopic();
```

### Using Transport Fields

For MQTT-specific configuration:

```java
return new DeviceMessage[] {
    DeviceMessage.forTopic("device/alarms")
        .payload(customJson)
        .retain(true)
        .transportField("qos", "2")  // MQTT QoS level
        .transportField("messageExpiryInterval", "3600")  // MQTT 5 property
        .transportField("contentType", "application/json")  // MQTT 5 property
        .build()
};
```

### Error Handling

**New pattern:**
```java
@Override
public CumulocityObject[] onMessage(Message<byte[]> message, DataPreparationContext context) {
    try {
        // Your processing logic
        return new CumulocityObject[] { /* ... */ };
    } catch (Exception e) {
        log.error("{} - Processing failed: {}", context.getTenant(), e.getMessage(), e);
        context.addWarning("Processing failed: " + e.getMessage());
        // Return empty array to indicate failure
        return new CumulocityObject[0];
    }
}
```

## Builder API Reference

### CumulocityObject Builders

#### Measurement Builder
```java
CumulocityObject.measurement()
    .type("c8y_TemperatureMeasurement")
    .time("2024-01-01T12:00:00Z")
    .fragment("c8y_Temperature", "T", 25.5, "C")  // fragment, series, value, unit
    .externalId("device-001", "c8y_Serial")
    .build()
```

#### Event Builder
```java
CumulocityObject.event()
    .type("c8y_LocationUpdate")
    .text("Device location updated")
    .time("2024-01-01T12:00:00Z")
    .property("c8y_Position", Map.of("lat", 52.5, "lng", 13.4))
    .externalId("device-001", "c8y_Serial")
    .attachment("logfile.txt", "text/plain", "base64data")  // Optional
    .build()
```

#### Alarm Builder
```java
CumulocityObject.alarm()
    .type("c8y_TemperatureAlarm")
    .severity("CRITICAL")  // CRITICAL, MAJOR, MINOR, WARNING
    .text("Temperature exceeds threshold")
    .status("ACTIVE")  // ACTIVE, ACKNOWLEDGED, CLEARED
    .time("2024-01-01T12:00:00Z")
    .externalId("device-001", "c8y_Serial")
    .deviceName("Temperature Sensor 1")  // Optional
    .build()
```

#### Operation Builder
```java
CumulocityObject.operation()
    .description("Restart device")
    .status("PENDING")
    .fragment("c8y_Restart", Map.of())
    .externalId("device-001", "c8y_Serial")
    .build()
```

#### Managed Object Builder
```java
CumulocityObject.managedObject()
    .name("Temperature Sensor")
    .type("c8y_Device")
    .fragment("c8y_IsDevice", Map.of())
    .externalId("device-001", "c8y_Serial")
    .build()
```

### Common Builder Methods

All builders support:
```java
.action("create")  // create, update, delete, patch
.externalId("device-001", "c8y_Serial")  // Can be called multiple times
.destination(Destination.ICEFLOW)  // CUMULOCITY (default) or ICEFLOW
.deviceName("My Device")  // For implicit device creation
.deviceType("c8y_DeviceType")  // For implicit device creation
.processingMode("TRANSIENT")  // PERSISTENT (default) or TRANSIENT
.contextData("key", "value")  // Custom metadata
```

### DeviceMessage Builder

```java
DeviceMessage.forTopic("device/messages")
    .payload("{\"temperature\": 25.5}")  // String, byte[], or Object
    .retain(false)  // or .retain() for true
    .clientId("device-001")
    .transportField("qos", "1")  // MQTT-specific fields
    .transportField("messageExpiryInterval", "3600")
    .time(Instant.now())
    .build()
```

## Testing Your Migration

### Unit Testing

The new pattern is easier to test:

```java
@Test
public void testOnMessage() {
    // Arrange
    ProcessorExtensionCustomAlarmNew extension = new ProcessorExtensionCustomAlarmNew();

    String jsonPayload = """
        {
            "externalId": "device-001",
            "type": "c8y_TemperatureAlarm",
            "alarmType": "CRITICAL",
            "message": "Temperature critical",
            "time": "2024-01-01T12:00:00Z"
        }
        """;

    Message<byte[]> message = new Message<>(
        jsonPayload.getBytes(),
        "device/alarms",
        "device-001",
        Map.of()
    );

    DataPreparationContext context = mock(DataPreparationContext.class);
    when(context.getMapping()).thenReturn(createMockMapping());
    when(context.getTenant()).thenReturn("test");

    // Act
    CumulocityObject[] results = extension.onMessage(message, context);

    // Assert
    assertNotNull(results);
    assertEquals(1, results.length);
    assertEquals(CumulocityType.ALARM, results[0].getCumulocityType());
    assertEquals("CRITICAL", getSeverityFromPayload(results[0]));
}
```

### Integration Testing

Test that both patterns work:

1. Deploy your extension
2. Create a mapping using your extension
3. Send test messages
4. Verify Cumulocity objects are created correctly
5. Check logs for pattern detection message

## Troubleshooting

### Pattern Not Detected

**Problem:** Extension still uses old pattern even after implementing `onMessage`.

**Solution:** Make sure you're overriding the method correctly:
```java
@Override  // Must have this annotation
public CumulocityObject[] onMessage(Message<byte[]> message, DataPreparationContext context) {
    // Your implementation
}
```

### extractFromSource Still Being Called

**Problem:** Framework calls `extractFromSource` instead of `onMessage`.

**Cause:** You're using `TransformationType.EXTENSION_SOURCE` instead of `EXTENSION_TARGET`.

**Solution:** Configure your mapping with `transformationType: EXTENSION_TARGET`.

### Context Not Available

**Problem:** `context.getFlowContext()` returns null.

**Solution:** Not all context features are available in all scenarios. Check for null:
```java
if (context.getManagedObjectByDeviceId(deviceId) != null) {
    // Use inventory lookup
}
```

### Build Errors

**Problem:** `CumulocityObject.alarm()` not found.

**Solution:** Make sure you're using version 2.0+ of the dynamic-mapper framework.

## Migration Checklist

- [ ] Update method signature to `onMessage`
- [ ] Change parameter from `ProcessingContext` to `Message`
- [ ] Change parameter from `C8YAgent` to `DataPreparationContext`
- [ ] Update payload access from `context.getPayload()` to `message.getPayload()`
- [ ] Replace manual request building with builder pattern
- [ ] Remove direct API calls (`c8yAgent.createMEAO()`, `context.addRequest()`)
- [ ] Return array of domain objects instead of void
- [ ] Implement stub `extractFromSource` method
- [ ] Add unit tests
- [ ] Test with existing mappings
- [ ] Verify logs show new pattern detected
- [ ] Update documentation

## Support and Questions

- Check the reference implementations:
  - Inbound: `ProcessorExtensionCustomAlarmNew.java`
  - Outbound: `ProcessorExtensionAlarmToCustomJsonNew.java`

- Review the interface JavaDoc:
  - `ProcessorExtensionInbound.java`
  - `ProcessorExtensionOutbound.java`

- For questions, contact the development team or open an issue on GitHub.
