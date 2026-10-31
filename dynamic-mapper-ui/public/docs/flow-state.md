---
title: Flow state
---

### Using state in Java Extension and Smart Function {#flow-state}

**Smart Functions** can keep state across multiple message invocations of the same mapping. This enables stateful
processing patterns such as message counters, running averages, min/max tracking, deduplication, and rate limiting,
without an external database. **Java Extensions** have the same state methods, but their values are not reliably kept
between messages, see [State in Java Extensions](#state-in-java-extensions).

State is scoped per **tenant** and **mapping**. All devices processed by the same mapping share the same state
bucket. A mapping cannot read or write the state of another mapping, even if both use the same key. To separate state
per device, prefix your keys with the client ID (see examples below).

#### Lifetime & TTL

State is held **in memory** and does not survive a microservice restart. Smart Function state is written back when
a message finishes processing.

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

Smart Functions use `context.getState(key)` and `context.setState(key, value)`. `context.getState(key, defaultValue)`
returns the default when the key is absent. `context.getStateKeySet()` lists the keys, and `context.clearState()`
removes all entries of the mapping. GraalVM automatically converts
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

:::caution
State is read when a message starts and written back when it finishes. Messages of the same mapping that are
processed at the same time do not see each other's changes, and the last message to finish overwrites the others.
Counters and other read-modify-write patterns can therefore lose updates under concurrent load. Use state for values
where an occasional lost update is acceptable, or for per-device keys that one device writes in sequence.
:::

#### State in Java Extensions {#state-in-java-extensions}

Java Extensions use `context.getNativeState(key)` and `context.setNativeState(key, value)`, which work with plain
Java objects (no GraalVM dependency). Setting a key to `null` removes it.

:::caution
Do not rely on Java Extension state to survive between messages. The mapper loads the stored state at the start of a
message, but values set with `setNativeState` are currently not saved back, so they last for one message only.
Outbound extensions have no state store at all. Keep long-lived values in a field only if your code is thread-safe, in
Cumulocity (for example a managed object fragment), or in an external store.
:::

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

