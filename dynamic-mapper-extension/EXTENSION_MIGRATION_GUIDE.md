# Extension Migration Guide: `DataPreparationContext` → `JavaExtensionContext`

This guide explains how to migrate external Java extensions from the old API
(used up to the `phoenix` release) to the current API.

---

## What Changed

| | Old API | New API |
|---|---|---|
| **Inbound interface** | `ProcessorExtensionInbound<O>` | `ProcessorExtensionInbound<O>` (same name) |
| **Inbound method** | `onMessage(Message<O>, DataPreparationContext)` | `onMessage(Message<O>, JavaExtensionContext)` |
| **Outbound interface** | `ProcessorExtensionOutbound<O>` | `ProcessorExtensionOutbound<O>` (same name) |
| **Outbound method** | `onMessage(Message<O>, DataPreparationContext)` | `onMessage(Message<O>, JavaExtensionContext)` |
| **Context type** | `DataPreparationContext` (removed) | `JavaExtensionContext` |

`DataPreparationContext` has been **removed**. The replacement is `JavaExtensionContext`,
which extends `DataPrepContext` and exposes the same core methods plus additional
Java-friendly helpers.

---

## Step-by-Step Migration

### 1. Update the import

Remove:
```java
import dynamic.mapper.processor.model.DataPreparationContext;
```

Add:
```java
import dynamic.mapper.processor.model.JavaExtensionContext;
```

### 2. Update the method signature

**Inbound — before:**
```java
@Override
public CumulocityObject[] onMessage(Message<byte[]> message, DataPreparationContext context) {
```

**Inbound — after:**
```java
@Override
public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
```

**Outbound — before:**
```java
@Override
public DeviceMessage[] onMessage(Message<Object> message, DataPreparationContext context) {
```

**Outbound — after:**
```java
@Override
public DeviceMessage[] onMessage(Message<Object> message, JavaExtensionContext context) {
```

### 3. No changes needed inside the method body

All methods that existed on `DataPreparationContext` are available on `JavaExtensionContext`:

| Method | Still available? |
|---|---|
| `context.getTenant()` | ✅ yes |
| `context.getMapping()` | ✅ yes |
| `context.addWarning(msg)` | ✅ yes |
| `context.addLog(msg)` | ✅ yes (was `addLogMessage`) |
| `context.getTesting()` | ✅ yes |
| `context.getClientId()` | ✅ yes |
| `context.setState(key, value)` | ❌ throws `UnsupportedOperationException`: use `setNativeState(key, value)` |
| `context.getState(key)` | ❌ throws: use `getNativeState(key)` |
| `context.getStateAll()` | ❌ throws: use `getNativeStateAll()` |
| `context.getManagedObject(id)` | ❌ throws: use `getManagedObjectAsMap(id)` |

The state and lookup methods that take or return a GraalVM `Value` belong to Smart Functions. A Java extension has no
GraalVM context, so they used to log a warning and do nothing; they now fail with the method to use instead.
### 4. New helpers available in `JavaExtensionContext`

These are added in the new API and are especially useful in pure Java extensions:

```java
// Look up a managed object from inventory cache — returns a plain Java Map
ExternalId extId = new ExternalId("device-001", "c8y_Serial");
Map<String, Object> device = context.getManagedObjectAsMap(extId);

// Look up by internal id, for example the sourceId of an outbound message
Map<String, Object> byId = context.getManagedObjectAsMap("12345");

// Atomic counter and de-duplication, safe when messages run at the same time
long received = context.incrementNativeState("received", 1);
if (context.isDuplicate(extId.getExternalId() + "|" + time, 60)) {
    return new CumulocityObject[0];
}

// Get the mapping config as a plain Java Map (instead of GraalVM Value)
Map<String, Object> config = context.getConfigAsMap();
```

---

## Complete Before / After Example

### Before (old `DataPreparationContext` API)

```java
package dynamic.mapper.processor.extension.external.inbound;

import dynamic.mapper.processor.extension.ProcessorExtensionInbound;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.DataPreparationContext;   // ← removed
import dynamic.mapper.processor.model.Message;

public class ProcessorExtensionMyDevice implements ProcessorExtensionInbound<byte[]> {

    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, DataPreparationContext context) {
        String tenant = context.getTenant();
        String externalIdType = context.getMapping().getExternalIdType();
        context.addWarning("Something unexpected happened");
        // ...
        return new CumulocityObject[0];
    }
}
```

### After (new `JavaExtensionContext` API)

```java
package dynamic.mapper.processor.extension.external.inbound;

import dynamic.mapper.processor.extension.ProcessorExtensionInbound;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.JavaExtensionContext;      // ← replacement
import dynamic.mapper.processor.model.Message;

public class ProcessorExtensionMyDevice implements ProcessorExtensionInbound<byte[]> {

    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
        String tenant = context.getTenant();
        String externalIdType = context.getMapping().getExternalIdType();
        context.addWarning("Something unexpected happened");
        // ...
        return new CumulocityObject[0];
    }
}
```

---

## Why the Error Occurs

If an extension JAR was compiled against the old API and loaded into a mapper that
uses the new API, the JVM throws:

```
java.lang.AbstractMethodError: Receiver class MyExtension does not define or inherit
an implementation of the resolved method
'abstract CumulocityObject[] onMessage(Message, JavaExtensionContext)'
of interface ProcessorExtensionInbound.
```

This is a **binary incompatibility**: the interface changed its method signature but
the extension class was not recompiled. The fix is to update the extension source
(two lines as shown above) and **recompile** against the new `dynamic-mapper-service` JAR.

---

## Checklist

- [ ] Replace `import dynamic.mapper.processor.model.DataPreparationContext` with `JavaExtensionContext`
- [ ] Update `onMessage` parameter type from `DataPreparationContext` to `JavaExtensionContext`
- [ ] Recompile against the current `dynamic-mapper-service` dependency
- [ ] Re-upload the extension JAR to Cumulocity
- [ ] Trigger an extension reload via the mapper UI or API
