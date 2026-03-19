# Exception Handling in Processor Extensions

This guide explains how to use the `ExtensionExceptionUtil` to get better error messages with file and line numbers when exceptions occur in your processor extensions.

## The Problem

Previously, when an exception occurred in a Java extension, the error message was not very helpful:

```
Test completed with warning: Failed to process custom measurement: Cannot invoke "Object.toString()" because the return value of "java.util.Map.get(Object)" is null
```

This doesn't tell you **WHERE** in your code the exception occurred, making debugging difficult.

## The Solution

Use `ExtensionExceptionUtil.formatExceptionWithLocation()` to automatically include the file name and line number:

```
Test completed with warning: Failed to process custom measurement at ProcessorExtensionCustomMeasurement.java:75: Cannot invoke "Object.toString()" because the return value of "java.util.Map.get(Object)" is null
```

Now you know exactly where to look!

## How to Use

### Basic Usage

Replace your exception handling code:

**Before:**
```java
try {
    // Your extension code
} catch (Exception e) {
    String errorMsg = "Failed to process: " + e.getMessage();
    log.error("{} - {}", context.getTenant(), errorMsg, e);
    context.addWarning(errorMsg);
    return new CumulocityObject[0];
}
```

**After:**
```java
import dynamic.mapper.processor.extension.ExtensionExceptionUtil;

try {
    // Your extension code
} catch (Exception e) {
    String errorMsg = ExtensionExceptionUtil.formatExceptionWithLocation(
        "Failed to process", e
    );
    log.error("{} - {}", context.getTenant(), errorMsg, e);
    context.addWarning(errorMsg);
    return new CumulocityObject[0];
}
```

### Detailed Location (Optional)

For even more detail, including the class and method name:

```java
String errorMsg = ExtensionExceptionUtil.formatExceptionWithDetailedLocation(
    "Failed to process", e
);
```

This produces:
```
Failed to process at ProcessorExtensionCustomMeasurement.onMessage(ProcessorExtensionCustomMeasurement.java:75): Cannot invoke "Object.toString()" ...
```

## Complete Example

Here's a complete example from `ProcessorExtensionCustomMeasurement.java`:

```java
package dynamic.mapper.processor.extension.external.inbound;

import com.dashjoin.jsonata.json.Json;
import dynamic.mapper.processor.extension.ExtensionExceptionUtil;
import dynamic.mapper.processor.extension.ProcessorExtensionInbound;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.DataPreparationContext;
import dynamic.mapper.processor.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;

import java.util.Map;

@Slf4j
public class ProcessorExtensionCustomMeasurement implements ProcessorExtensionInbound<byte[]> {

    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, DataPreparationContext context) {
        try {
            // Parse JSON payload
            String jsonString = new String(message.getPayload(), "UTF-8");
            Map<String, Object> jsonObject = (Map<String, Object>) Json.parseJson(jsonString);

            // Extract fields (this is where NPE might occur if field is missing!)
            String externalId = jsonObject.get("externalId").toString(); // Line 75
            DateTime time = new DateTime(jsonObject.get("time"));
            Number temperature = (Number) jsonObject.get("temperature");
            String unit = jsonObject.get("unit").toString();

            // Build measurement
            return new CumulocityObject[] {
                CumulocityObject.measurement()
                    .type("c8y_TemperatureMeasurement")
                    .time(time.toString())
                    .fragment("c8y_Temperature", "T", temperature.doubleValue(), unit)
                    .externalId(externalId, context.getMapping().getExternalIdType())
                    .build()
            };

        } catch (Exception e) {
            // NEW: Use ExtensionExceptionUtil for better error messages
            String errorMsg = ExtensionExceptionUtil.formatExceptionWithLocation(
                "Failed to process custom measurement", e
            );
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            return new CumulocityObject[0];
        }
    }
}
```

## Benefits

1. **Faster Debugging**: Immediately see which line caused the exception
2. **Better Error Messages**: Users get more actionable error information
3. **Easier Troubleshooting**: No need to add debug logging to find the issue
4. **Consistent**: Use the same pattern across all extensions

## Migration Guide

To update all your existing extensions:

1. Add the import:
   ```java
   import dynamic.mapper.processor.extension.ExtensionExceptionUtil;
   ```

2. Replace your catch block error message construction:
   ```java
   // OLD
   String errorMsg = "Failed to process: " + e.getMessage();

   // NEW
   String errorMsg = ExtensionExceptionUtil.formatExceptionWithLocation(
       "Failed to process", e
   );
   ```

3. Keep the rest of your error handling the same (logging, adding warnings, etc.)

## API Reference

### `formatExceptionWithLocation(String prefix, Exception e)`

Creates an error message with the file and line number.

**Parameters:**
- `prefix` - Your custom error message prefix (e.g., "Failed to process custom measurement")
- `e` - The exception that was thrown

**Returns:** A formatted string like:
```
Failed to process custom measurement at ProcessorExtensionCustomMeasurement.java:75: Cannot invoke "Object.toString()" because...
```

### `formatExceptionWithDetailedLocation(String prefix, Exception e)`

Creates an error message with class name, method name, file, and line number.

**Parameters:**
- `prefix` - Your custom error message prefix
- `e` - The exception that was thrown

**Returns:** A formatted string like:
```
Failed to process custom measurement at ProcessorExtensionCustomMeasurement.onMessage(ProcessorExtensionCustomMeasurement.java:75): Cannot invoke "Object.toString()" because...
```

## How It Works

The utility examines the exception's stack trace and finds the first stack trace element that belongs to your extension code (packages starting with `dynamic.mapper.processor.extension.external` or `dynamic.mapper.processor.extension.internal`). It then extracts the file name and line number from that element.

This filters out framework code and gives you the exact location in **your** code where the problem occurred.
