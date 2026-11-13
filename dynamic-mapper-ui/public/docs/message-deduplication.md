---
title: Message de-duplication
---

### Message de-duplication {#message-deduplication}

Brokers and gateways sometimes deliver a message twice, for example after a reconnect or an MQTT QoS 1 retry. A
mapping then creates the same measurement twice. De-duplication drops the second copy: the mapping remembers a key for
each message for a short time, and a message whose key it has already seen is not processed.

It works for **inbound** mappings only. De-duplication is a rule you write: the mapping does not drop anything until
you add the check.

#### Add the check

Choose the place that matches the transformation type of the mapping.

| Mapping | Where | Call |
|---------|-------|------|
| JSONata and Java extension | **Execution filter** of the mapping | `$isDuplicate(key, windowSeconds)` |
| Smart Function | The function code | `context.isDuplicate(key, windowSeconds)` |

Both calls return `true` if the same key was seen for this mapping less than `windowSeconds` ago, and record the key
otherwise. Both share one store, so the behavior is the same.

##### JSONata and Java extension

Negate the result in the execution filter:

```javascript
// Drop a second message from the same device with the same timestamp, for 60 seconds
$exists(deviceId) and $exists(time) and $not($isDuplicate(deviceId & "|" & time, 60))

// Combine it with another condition
telemetry.telemetryReadings[0].value > 10.00 and $not($isDuplicate(deviceId & "|" & time, 60))

// Drop exact repeats of the whole payload
$not($isDuplicate($string($), 60))
```

##### Smart Function

Return an empty array for a repeat:

```javascript
function onMessage(msg, context) {
    var payload = msg.payload;
    var externalId = context.getClientId() || payload["externalId"];
    var time = payload["time"];

    // Without both fields there is no safe key, so the message is processed without a check.
    var key = externalId && time ? externalId + "|" + time : null;
    if (key && context.isDuplicate(key, 60)) {
        return [];
    }
    // ... create the measurement
}
```

The sample mapping **IN-19 Smart Function - drop repeated messages** contains this code, and **IN-18 JSONata - drop
repeated messages** contains the filter version. You can import both from the sample mappings.

#### Choose the key and the window

- **Key:** any value that identifies one message. Text is used as it is. A whole number has no decimal point, so `5.0`
  and `5` are the same key. An object is converted to JSON text with its members sorted by name, so two messages that
  list the members in another order still match. Join several fields with a separator such as `"|"`, so that `a` and
  `bc` differ from `ab` and `c`.
- **Per mapping:** the keys of a mapping are separate from every other mapping and tenant. Do not add the mapping or the
  tenant to the key. The topic is not part of the key either.
- **Missing fields:** the `&` operator in JSONata and the `+` operator in JavaScript turn a missing field into text,
  so every message of a device without `time` would count as a repeat of the first one. Check the fields before the
  call, as in the examples above.
- **Window:** a whole number of seconds from 1 to 3600. The window starts at the first message and does not restart
  when a repeat arrives. Choose about the retry interval of your broker: a short window needs less memory.
- **Invalid input:** a key without a value, or a window outside 1 to 3600 (for example 7200 or 0.5), is not shortened.
  The message is processed without a check and the service logs a warning.

#### What happens to messages that fail

A key is recorded when the check runs. If the message then fails, for example because Cumulocity rejects a request, or
if another condition rejects it, the service gives the key back, so the broker's retry is processed. A copy that
arrived while the first was still being processed is already dropped and is not processed again.

#### See what was dropped

The **Repeated** column in **Statistic processed** counts the messages a mapping dropped as repeats. They are a
part of **Filtered**, not an addition to it. See [Monitoring](/c8y-pkg-dynamic-mapper/introduction/monitoring).

#### Testing

Dry Run and Live Test never record a key, so testing does not change what production drops. In the editor, the
**Filter Result** field always shows `false` for `$isDuplicate`, because the editor has no message history. To test
the behavior, send the same message twice to the connector and look at the **Repeated** column.

#### Editing a mapping

Changing the execution filter, the Smart Function code, or the transformation type removes the recorded keys of the
mapping, because the keys then have a new meaning. A repeat that arrives right after such a save is processed. Other
changes, such as a new name or activating the mapping, keep the keys. Deleting the mapping removes them too.

#### Limits

- **Memory only:** the service keeps keys in memory. They are lost on a restart and are not shared between service
  instances. A repeat that arrives after a restart, or on another instance, is processed.
- **Bounded memory:** a mapping keeps at most 100,000 keys, and all mappings together keep at most 1,000,000. When a
  limit is reached, the service removes expired keys first. If it is still full, new messages are processed without a
  check, keys already stored still match, and the service logs one warning per minute with the number of unchecked
  messages. If you see this warning, shorten the window or use a key with fewer different values.
- **Exact repeats only:** the check does not detect near duplicates, such as the same timestamp with a changed value.
- **Outbound mappings:** `$isDuplicate` is ignored with a warning and always returns `false`.
