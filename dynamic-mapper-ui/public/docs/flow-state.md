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
timer is kept **per mapping** and measures the time since that mapping's *last write* to its state. Reading state does
not restart it. If a mapping has not written state for longer than the configured duration, the state of that mapping
is cleared on the next scheduled cleanup cycle. Other mappings are not affected: a busy mapping does not keep an idle
one alive, and a mapping that wrote recently keeps its state when another expires.

For example, with a value of `1440` (the default, equal to 24 hours): a mapping that has written no state for 24 hours
loses its stored state, while a mapping that wrote an hour ago keeps it. This prevents unbounded memory growth when
mappings are inactive.

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
State is read when a message starts, and the keys the message changed with `setState` are written back when it
finishes. Messages of the same mapping that are processed at the same time do not see each other's changes while they
run. A message that only reads state, or writes other keys, does not affect them. If two messages write the **same**
key, the last one to finish wins, so counters and other read-modify-write patterns can lose updates under concurrent
load. Use state for values where an occasional lost update is acceptable, or for per-device keys that one device
writes in sequence.

`context.getState(key)` returns a copy. Changing the returned object does not change the state: call `setState`
with the new value.
:::

#### State in Java Extensions {#state-in-java-extensions}

Java Extensions use `context.getNativeState(key)` and `context.setNativeState(key, value)`, which work with plain
Java objects (no GraalVM dependency). Setting a key to `null` removes it.

:::caution
The state works as for Smart Functions. The mapper loads a copy when the message starts and writes back the keys you
changed with `setNativeState` after `onMessage` returns normally, for inbound and outbound extensions. It also writes
them when you return an empty result, so an extension can record what it saw and return nothing. If `onMessage` throws,
the message fails and nothing is written, so the retry does not look like a duplicate. A test run starts from an empty
state and writes nothing.

Messages of the same mapping run in parallel. A message that only reads state does not affect the others, but if two
messages write the **same** key, the last one to finish wins. Nested lists and maps you read are copies, so changing them
does not affect other messages until you call `setNativeState` again. The state is held in service memory and is lost on
restart.
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

