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
load. To combine several messages into one, use [`context.collect`](#collect), which is atomic. Use state for values where an occasional lost update is acceptable, or for per-device keys that one device
writes in sequence.

`context.getState(key)` returns a copy. Changing the returned object does not change the state: call `setState`
with the new value.
:::

#### Many messages into one: `context.collect` {#collect}

Some devices send one MQTT message per value. A Unified Namespace publisher, for example, sends 100 messages for the
same timestamp, with the measurement name in the topic and only `time` and `value` in the payload. To create **one**
measurement with 100 fragments, the mapping must collect the values until the group is complete.

Do not build this with `getState` and `setState`. Of 100 messages that run at the same time, none sees the other 99,
so a count made from state never reaches 100 and no measurement is created. Use `context.collect` instead. It adds a
value to a named group and checks the count in one atomic step.

```javascript
function onMessage(msg, context) {
  const parts = msg.topic.split("/");           // uns/plant1/press7/temperature
  const name = parts[parts.length - 1];
  const deviceId = parts[parts.length - 2];

  // One group per device and timestamp, complete after 100 values
  const values = context.collect(deviceId + "|" + msg.payload.time, name, msg.payload.value, 100);
  if (!values) {
    return [];                                  // group still incomplete
  }

  const measurement = { time: msg.payload.time, type: "uns_Measurement" };
  Object.keys(values).forEach(function (n) {
    measurement["uns_" + n] = { [n]: { value: values[n] } };
  });
  return [{
    cumulocityType: "measurement",
    action: "create",
    payload: measurement,
    externalSource: [{ type: "c8y_Serial", externalId: deviceId }]
  }];
}
```

`context.collect(groupKey, itemKey, value, completeWhen)` returns `null` until the group is complete. The call that
completes it receives the group as an object of item key to value, and the group is removed. A repeated item key
replaces the earlier value and does not count twice. `completeWhen` is a number (the group holds that many different
item keys) or an object, where the first condition that holds completes the group:

| Option | The group is complete when |
|---|---|
| `count` | it holds this many different item keys |
| `keys` | all of these item keys are present, for example `["temperature", "pressure"]` |
| `last` | this message says it is the last one, for example `payload.last === true` |
| `maxAgeSeconds` | not a condition: incomplete groups older than this are dropped when a call with this option arrives |

**Publishers that do not send a fixed count.** If the number of values changes, flush when a newer timestamp shows
that the previous group is finished. `context.openGroups(prefix)` lists the open groups of a device and
`context.take(groupKey)` removes one as it is, even if it is partial:

```javascript
function onMessage(msg, context) {
  const parts = msg.topic.split("/");
  const name = parts[parts.length - 1];
  const deviceId = parts[parts.length - 2];
  const current = deviceId + "|" + msg.payload.time;

  // A group of this device that is not the current one will get no more values: flush it
  const out = [];
  context.openGroups(deviceId + "|").forEach(function (key) {
    if (key !== current) {
      const partial = context.take(key);
      if (partial) {
        out.push(toMeasurement(deviceId, key.split("|")[1], partial));  // your function
      }
    }
  });

  // Never complete by count: the next timestamp flushes it
  context.collect(current, name, msg.payload.value, { last: false, count: 10000, maxAgeSeconds: 3600 });
  return out;
}
```

A complete mapping is in the sample set as *IN-21 Smart Function - flush the previous UNS timestamp when a newer one
arrives*. It assumes that the values of one timestamp arrive before the values of the next.

The last group stays in memory until the next timestamp arrives or `maxAgeSeconds` passes. A partial group is
created only when the next timestamp arrives, so a device that stops sending leaves its last group unsent.

:::caution
- Groups are held **in memory**. They are lost on a restart and are not shared between service instances.
- The mapper does not know when a group is "finished" unless a condition is met. A group that never completes stays
  in memory until the mapping's state expires, the mapping is deleted, or `clearState()` is called. A mapping keeps at
  most 1,000 incomplete groups and drops the oldest when one more opens.
- The group is removed when it is handed out. If the function fails afterwards, those values are lost.
- In Dry Run and Live Test, `collect` returns `null` and buffers nothing, so a test shows only the incomplete case.
  Send the real messages to see the grouped measurement.
- A license counts the larger of the incoming messages and the created measurements, so 100 messages in and one
  measurement out still count as 100.
:::

A complete mapping is in the sample set as *IN-20 Smart Function - group single-value UNS messages into one measurement*.

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

