---
title: Flow state
---

### Using state in Java Extension and Smart Function {#flow-state}

Both **Java Extensions** and **Smart Functions** can maintain persistent state across multiple message invocations
for the same mapping. This enables stateful processing patterns such as message counters, running averages,
min/max tracking, deduplication, and rate limiting — without requiring an external database.

State is scoped per **tenant** and **mapping**. All devices processed by the same mapping share the same state
bucket. To separate state per device, prefix your keys with the client ID (see examples below).

#### Lifetime & TTL

State is held **in-memory** and does not survive a microservice restart.

The **Minutes lifetime flow state** setting (in Service Configuration → Caching) controls automatic expiry. The
timer measures the time since the *last write* to that tenant's state store. If no state has been written for
longer than the configured duration, the **entire state** for that tenant is cleared on the next scheduled
cleanup cycle (which runs every minute).

For example, with a value of `1440` (the default, equal to 24 hours): if no mapping has written any state for 24
hours, all stored state for that tenant is discarded. This prevents unbounded memory growth when mappings are
inactive.

Set to `0` to disable automatic expiry entirely. Flow state can also be cleared immediately via the **Clear flow
state** button on the Caching configuration page.

#### State in Smart Functions (JavaScript)

Smart Functions use `context.getState(key)` and `context.setState(key, value)`. GraalVM automatically converts
between JavaScript and Java types, so you can pass native JavaScript values directly. `getState` returns `null`
(not `undefined`) when a key is absent.

**Global counter (shared across all devices):**

```javascript
function onMessage(message, context) {
  // Increment a counter shared by all devices using this mapping
  const count = context.getState("messageCount");
  context.setState("messageCount", count !== null ? count + 1 : 1);

  // ... rest of processing
}
```

**Per-device counter (prefix key with clientId):**

```javascript
function onMessage(message, context) {
  const clientId = context.getClientId();
  const key = clientId + ".messageCount";

  const count = context.getState(key);
  context.setState(key, count !== null ? count + 1 : 1);

  // ... rest of processing
}
```

#### State in Java Extensions

Java Extensions use `context.getNativeState(key)` and `context.setNativeState(key, value)`, which work with plain
Java objects (no GraalVM dependency). Setting a key to `null` removes it. State is persisted to the
`FlowStateStore` automatically after each invocation.

**Global counter (shared across all devices):**

```java
@Override
public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
    // Increment a counter shared by all devices using this mapping
    Integer count = (Integer) context.getNativeState("messageCount");
    context.setNativeState("messageCount", count == null ? 1 : count + 1);

    // ... rest of processing
}
```

**Per-device counter (prefix key with clientId):**

```java
@Override
public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
    String clientId = context.getClientId();
    String key = clientId + ".messageCount";

    Integer count = (Integer) context.getNativeState(key);
    context.setNativeState(key, count == null ? 1 : count + 1);

    // ... rest of processing
}
```

**Available state methods in Java Extensions:**

| Method | Description |
|---|---|
| `setNativeState(key, value)` | Store any Java object. Passing `null` as value removes the key. |
| `getNativeState(key)` | Retrieve a stored value, or `null` if not present. |
| `getNativeStateAll()` | Return an unmodifiable view of the entire state map for this mapping. |

