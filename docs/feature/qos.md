# Quality of Service (QoS)

Every mapping carries a `qos` field — the **delivery guarantee it asks for**. This page
describes where that value comes from, how it is consolidated when several mappings match
one message, how each connector translates it onto its own protocol primitives, and what
the UI shows.

The model is MQTT's, because that is the protocol the feature originated from:

| `Qos` constant | Level | Meaning |
|---|---|---|
| `AT_MOST_ONCE` | 0 | Fire and forget. The message is acknowledged towards the broker *before* the mapping runs, so a processing failure loses it. Lowest latency. |
| `AT_LEAST_ONCE` | 1 | The message is acknowledged only *after* the pipeline reported success. A failure causes a redelivery, so the same message can be processed more than once. |
| `EXACTLY_ONCE` | 2 | As at-least-once, plus broker-side duplicate suppression. Only MQTT and Pulsar implement this. |

The enum is [`Qos`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/Qos.java).
It is the single source of truth for the ordering (`getLevel()`), the null default
(`Qos.DEFAULT` = `AT_LEAST_ONCE`), the "stronger of two" rule (`Qos.max`), and the
capability clamp (`Qos.clampTo`). Call sites must not compare `ordinal()` values
themselves. The JSON wire format is unchanged — the enum **name** (`"AT_LEAST_ONCE"`).

---

## Where QoS is set

| Layer | Field | Notes |
|---|---|---|
| Mapping | [`Mapping.qos`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/Mapping.java) | `@Builder.Default` + `@JsonSetter(nulls = SKIP)` → a mapping created through the builder or through the API with `"qos": null` gets `AT_LEAST_ONCE` instead of `null`. |
| UI | QoS picker in the mapping *Properties* step | See [UI](#ui) below. |
| Message | the QoS the publisher used | Inbound only, and only for MQTT — see [the two-sided bound](#the-two-sided-bound-inbound). |

`qos` is `@NotNull` and part of the structural Bean Validation contract described in
[`mapping-validation.md`](mapping-validation.md). There is no *business* rule on it — a
mapping is never rejected for asking for a level its connector cannot provide; it is
clamped at runtime instead (and the UI warns about it up front).

---

## How QoS flows through a message

```
Mapping.qos (per mapping)
   │
   │  resolve all mappings matching the topic / the C8Y object
   ▼
AConnectorClient.determineMaxQosInbound/Outbound()   ── strongest requested level …
   │                                                    … then adjustQos() clamps it
   ▼
ProcessingResultWrapper.consolidatedQos
   │
   ├── inbound  → the broker callback decides when to ACK the message
   └── outbound → AConnectorClient.effectivePublishQos(context) → connector publish
```

### Consolidation

One inbound message can match several mappings, each with its own `qos`. The transport
can only make **one** ack decision for that message, so
`AConnectorClient.determineMaxQos*()` takes the **strongest** requested level — the
strictest mapping wins, and no mapping ever gets a weaker guarantee than it asked for
(the other direction, giving a mapping *more* than it asked for, is harmless).

The result lands in
[`ProcessingResultWrapper.consolidatedQos`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/model/ProcessingResultWrapper.java),
whose getter is null-safe: early-exit paths (no mapping resolved, unparseable payload)
never set it, and every caller would otherwise have to guard before reading the level.

### The two-sided bound (inbound)

For MQTT, the guarantee actually achievable is bounded on *both* ends — see
[`AbstractMqttCallback`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/mqtt/AbstractMqttCallback.java):

```java
int effectiveQos = Math.min(publishQos, mappingQos);
```

A message the device published at QoS 0 is gone the moment it is delivered; no mapping
setting can turn that into at-least-once. Conversely, a QoS 2 publish processed by a
mapping configured as `AT_MOST_ONCE` is acknowledged immediately — the mapping opted out
of the guarantee.

If `effectiveQos > 0` the callback defers the ACK until the pipeline reports success (and
reconnects/re-delivers on error, bounded by the poison-pill counter); otherwise it acks
straight away.

### Subscriptions

Inbound QoS is also a **subscription** parameter, managed by
[`MappingSubscriptionManager`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/core/client/MappingSubscriptionManager.java):

- Several mappings can share one topic; the topic is subscribed at the **highest** QoS
  among them, reference-counted so it stays subscribed while any mapping needs it.
- Adding a mapping with a higher QoS to an already-subscribed topic re-subscribes at the
  higher level (`addSubscriptionInbound`, and `upgradeQosForRetainedTopics` on a full
  reconcile). Without that, the topic would keep running at whatever QoS the mapping that
  happened to subscribe first asked for.
- QoS is **not** downgraded when the high-QoS mapping is removed; the topic simply stays
  at the higher level until it is unsubscribed. Over-delivering is safe and avoids a
  re-subscribe storm on every mapping edit.

---

## Connector capabilities and clamping

Not every broker can implement every level. Each connector declares what it supports in
`AConnectorClient.supportedQos`, and **every** QoS that reaches the broker passes through
`AConnectorClient.adjustQos()`:

- on subscribe — the `SubscriptionCallback` in `initializeManagers()` wraps every
  `subscribe(topic, qos)` call, so no connector can forget it;
- on publish — connectors call `effectivePublishQos(context)` instead of reading
  `context.getQos()` directly;
- on consolidation — `determineMaxQos()` clamps the consolidated level too, so the ack
  logic never waits for a guarantee the transport does not provide.

`Qos.clampTo` picks the **strongest supported level that is not stronger than requested**;
if the connector supports nothing that weak (HTTP/WebHook are always at-least-once), it
returns the weakest level the connector offers. Over-delivering is safe; silently running
at an unsupported level is not. Each clamp is logged once at `WARN` with the connector
name, so an operator can see why a mapping is not running at its configured level.

| Connector | Supported | How the level is realised |
|---|---|---|
| MQTT 3.1.1 / MQTT 5 | 0, 1, 2 | Native MQTT QoS, both on `subscribe` and on `publish`. |
| Pulsar | 0, 1, 2 | 0 → async send, no ack; 1/2 → ack after processing, `acknowledgmentGroupTime` enabled; 2 additionally uses an `Exclusive` subscription. |
| Cumulocity MQTT Service (Pulsar) | 0, 1 | Matches the service, which implements [QoS 0 and 1 only](https://cumulocity.com/docs/device-integration/mqtt-service/); `EXACTLY_ONCE` → `AT_LEAST_ONCE`. Outbound, the level selects the *Pulsar* producer send mode (0 → `sendAsync`, 1 → awaited `send`), not the MQTT QoS towards the device. Inbound ignores it — always at-least-once. See [`connector-mqtt-service.md`](connector-mqtt-service.md#qos). |
| AMQP 0.9.1 | 0, 1 | `deliveryMode` 1 (non-persistent) for level 0, 2 (persistent) for anything above; consumer `autoAck` only for level 0. |
| AMQP 1.0 | 0, 1 | JMS `NON_PERSISTENT` / `PERSISTENT` by the same rule. |
| Kafka | 0, 1 | Level 0 commits the offset immediately; level 1 defers the commit until the pipeline reported success. Exactly-once would need transactional consume-process-produce and is not implemented. |
| Google Pub/Sub | 0, 1 | Level 0 acks before processing; level 1 acks after success and `nack`s on error so Pub/Sub redelivers. |
| HTTP | 1 only | The HTTP response is sent after the pipeline ran, i.e. inherently at-least-once. A mapping asking for level 0 is *upgraded*. |
| WebHook | 1 only | Outbound only; an HTTP call either succeeds or is retried. |
| Test connector | 0, 1, 2 | Simulated — never clamps, so mappings can be exercised at their configured level. |

The capability is also published to the frontend: `AConnectorClient.getConnectorSpecification()`
stamps `supportedQos` onto the
[`ConnectorSpecification`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/core/ConnectorSpecification.java)
returned by the connector-specification endpoint, so the capability is declared exactly
once — on the client — and never repeated in each `createConnectorSpecification()`.

### Adding a connector

Set `this.supportedQos` in the constructor (before `createConnectorSpecification()`), and
use `effectivePublishQos(context)` in `publishMEAO`. Nothing else is required — subscribe
clamping and the specification are handled by the base class. Declare only what the
connector *actually implements*: the declaration drives both the runtime clamp and what
the UI offers.

---

## UI

| Place | File |
|---|---|
| QoS metadata (labels, per-level description, `clampQos`) | [`shared/mapping/mapping.model.ts`](../../dynamic-mapper-ui/src/shared/mapping/mapping.model.ts) |
| QoS column in the mapping grid | [`mapping/renderer/qos.renderer.component.ts`](../../dynamic-mapper-ui/src/mapping/renderer/qos.renderer.component.ts) |
| QoS picker in the mapping *Properties* step | [`mapping/step-property/mapping-properties.component.ts`](../../dynamic-mapper-ui/src/mapping/step-property/mapping-properties.component.ts) |
| Connector capability in the specification model | [`shared/connector-configuration/connector.model.ts`](../../dynamic-mapper-ui/src/shared/connector-configuration/connector.model.ts) |

`QOS_OPTIONS` is the frontend counterpart of the backend enum — one entry per level with
its label, numeric level and a one-sentence explanation. Both the grid renderer and the
picker read from it, so a level can never be labelled differently in two places.
`clampQos()` mirrors `Qos.clampTo` so the UI can predict the backend's decision.

The picker:

- lists the levels with their human-readable labels (`At most once`, …);
- shows the selected level's explanation underneath, recomputed on every change;
- appends a warning naming every connector the mapping is deployed to that cannot honour
  the selected level, and what it will be handled as — e.g. *"NOTE: this level is not
  supported by: Kafka Prod (handled as at least once)."* The capability is resolved from
  the deployed connectors' specifications via the `deploymentMapEntry` input; if the
  specifications cannot be loaded, the warning is simply omitted.

**Known asymmetry:** for `OUTBOUND` mappings the picker hides `EXACTLY_ONCE`, even though
MQTT and Pulsar can publish at level 2. This is a deliberate UI restriction, not a
transport limitation.

---

## Gotchas

- **A stronger request never means a weaker guarantee.** AMQP used to test
  `qos == AT_LEAST_ONCE` for persistence, which made `EXACTLY_ONCE` fall into the
  *non-persistent* branch. Test `Qos.requiresAcknowledgement()` (i.e. "above level 0")
  instead of equality with a single constant.
- **QoS > 0 costs throughput.** The message is not acknowledged until the pipeline
  finishes, so a slow mapping (Smart Function + several C8Y REST calls) throttles the
  subscription. Use `AT_MOST_ONCE` for high-volume telemetry that tolerates loss.
- **At-least-once means duplicates.** A redelivery re-runs the whole mapping — including
  device auto-creation and measurement creation. Mappings must be idempotent or tolerate
  duplicate measurements.
- **QoS is per mapping, not per connector**, but is *realised* per connector. The same
  mapping deployed to MQTT and to Kafka runs at level 2 on the former and at level 1 on
  the latter.
- **QoS is a transport setting, not an end-to-end guarantee.** It governs the leg between
  the broker and this service. What happens beyond that is the broker's business: over
  Cumulocity MQTT Service, for example, mandatory clean sessions mean a message sent to a
  disconnected device is dropped regardless of the level configured here.

## Tests

| Test | Covers |
|---|---|
| [`QosTest`](../../dynamic-mapper-service/src/test/java/dynamic/mapper/model/QosTest.java) | Levels, `max`/`min`/`orDefault`, `clampTo` (downgrade, upgrade, no-restriction), the JSON wire format, and the `Mapping.qos` default. |
| [`MappingSubscriptionManagerTest`](../../dynamic-mapper-service/src/test/java/dynamic/mapper/connector/core/client/MappingSubscriptionManagerTest.java) | Max-QoS-per-topic, QoS upgrade on add and on reconcile, reference counting. |
| [`AMQPClientTest`](../../dynamic-mapper-service/src/test/java/dynamic/mapper/connector/amqp/AMQPClientTest.java) | Clamping to a connector's capability, and `supportedQos` reaching the specification. |
| [`MQTT3ClientTest`](../../dynamic-mapper-service/src/test/java/dynamic/mapper/connector/mqtt/MQTT3ClientTest.java) | MQTT never clamps; `null` falls back to the default. |
| [`GooglePubSubClientTest`](../../dynamic-mapper-service/src/test/java/dynamic/mapper/connector/googlepubsub/GooglePubSubClientTest.java) | Ack-before-processing vs. ack-after-success / nack-on-error. |
