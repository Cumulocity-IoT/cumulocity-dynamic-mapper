# Reliability: delivery guarantees and failure handling

What happens to a message when something goes wrong. Two independent mechanisms, both
configured per mapping, plus the transport-level retry that sits underneath them:

| Mechanism | Mapping field | Question it answers | Section |
|---|---|---|---|
| Delivery guarantee | `qos` | When may the broker message be acknowledged — before processing, or only after it succeeded? | [Quality of Service](#quality-of-service) |
| Failure threshold | `maxFailureCount` | When should a mapping that keeps failing be taken out of service? | [Failure handling](#failure-handling) |
| Poison-pill guard | — (fixed at 5) | How often may one message be redelivered before it is dropped? | [The poison-pill guard](#the-poison-pill-guard) |

They compose: `qos > 0` is what causes a failed message to be redelivered at all;
`maxFailureCount` is what stops a mapping that fails every time; the poison-pill guard is
what stops a single bad message from being redelivered forever.

---

## Quality of Service

Every mapping carries a `qos` field — the **delivery guarantee it asks for**. This section
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

### Where QoS is set

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

### How QoS flows through a message

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

#### Consolidation

One inbound message can match several mappings, each with its own `qos`. The transport
can only make **one** ack decision for that message, so
`AConnectorClient.determineMaxQos*()` takes the **strongest** requested level — the
strictest mapping wins, and no mapping ever gets a weaker guarantee than it asked for
(the other direction, giving a mapping *more* than it asked for, is harmless).

The result lands in
[`ProcessingResultWrapper.consolidatedQos`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/model/ProcessingResultWrapper.java),
whose getter is null-safe: early-exit paths (no mapping resolved, unparseable payload)
never set it, and every caller would otherwise have to guard before reading the level.

#### The two-sided bound (inbound)

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

#### Subscriptions

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

### Connector capabilities and clamping

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

#### Adding a connector

Set `this.supportedQos` in the constructor (before `createConnectorSpecification()`), and
use `effectivePublishQos(context)` in `publishMEAO`. Nothing else is required — subscribe
clamping and the specification are handled by the base class. Declare only what the
connector *actually implements*: the declaration drives both the runtime clamp and what
the UI offers.

---

### QoS in the UI

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

### QoS gotchas

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

---

## Failure handling

QoS decides whether a *failed message* is redelivered. `maxFailureCount` decides when a
*failing mapping* is taken out of service — a mapping whose template no longer matches the
payload, whose Smart Function throws on every message, or whose target device was deleted
would otherwise keep failing (and, at QoS > 0, keep forcing redeliveries) indefinitely.

| Field | Where | Meaning |
|---|---|---|
| [`Mapping.maxFailureCount`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/Mapping.java) | per mapping, set in the editor | Number of **consecutive** failures after which the mapping is deactivated. **0 (the default) disables the check.** |
| [`MappingStatus.currentFailureCount`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/MappingStatus.java) | runtime status, shown in *Monitoring* | The current streak. |
| `MappingStatus.errors` | runtime status | Lifetime error count — never reset by processing, purely informational. |

### The counter is a streak, not a total

`currentFailureCount` is incremented by every failed message and reset to 0 by:

- the first message that completes **without an error**
  ([`ConsolidationProcessor`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/util/ConsolidationProcessor.java),
  the single terminal point every pipeline leg passes through), and
- **(re)activation** of the mapping (`MappingService.setActivationMapping`).

This matters: with a cumulative counter, a healthy mapping processing millions of messages
at a 0.01 % transient error rate would eventually cross any threshold and be disabled. The
threshold is meant to catch a *persistently* broken mapping, so only an unbroken run of
failures counts.

The reset is deliberately cheap on the hot path — a mapping that did not opt in
(`maxFailureCount == 0`) or that has no failures pending costs a map lookup and a volatile
read, and never takes the status monitor. Test runs (`context.isTesting()`) never touch the
counter.

### What happens at the threshold

The failure that makes `currentFailureCount >= maxFailureCount` triggers, via
[`MappingService.increaseAndHandleFailureCount()`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/service/MappingService.java):

1. A `MAPPING_FAILURE_EVENT` logging event carrying `mappingId` and `failureCount`.
2. A real deactivation through the regular `setActivationMapping(tenant, id, false, null)`
   path — the mapping is persisted as inactive and the cache is refreshed.
3. A subscription update on every connector the mapping is deployed to, so inbound
   connectors release its topic (reference-counted, so a topic shared with another active
   mapping stays subscribed) and outbound connectors drop it from their applied set.
   `setActivationMapping` does **not** do this by itself — the REST controllers notify the
   connectors separately after calling it, and this path has to do the same. Without it the
   mapping would be flagged inactive while its topic stayed subscribed, and every further
   message would keep failing.

The deactivation runs **asynchronously** on the virtual-thread pool: this code path is on a
Camel processing thread, where taking the per-mapping activation lock and doing a blocking
Cumulocity round-trip would stall the pipeline. A `deactivationsInFlight` guard collapses
the burst of failures that typically arrives together — in-flight messages all failing for
the same reason — into a single deactivation.

Reactivating the mapping (UI or `PUT /mapping/{id}/activation`) clears the streak, so it
starts with a clean slate.

> **Note:** before this was implemented, the threshold only produced the logging event —
> `handleFailureThresholdExceeded()` never actually deactivated anything, despite the
> editor promising "if this is exceeded the mapping is automatically deactivated". If you
> are looking at an older release, `maxFailureCount` is inert there.

### What counts as a failure

Any processor calling `mappingService.increaseAndHandleFailureCount(...)` — deserialization
errors, enrichment/identity-resolution errors, JSONata and Smart Function evaluation errors,
extension errors, and failures sending to Cumulocity. A message **filtered out** by
`filterMapping`/`filterInventory` is not a failure; nor is a test run.

### The poison-pill guard

Independently of the mapping-level threshold, every callback that can request redelivery
caps how often it will do so for the *same message*: `MAX_CONSECUTIVE_FAILURES` /
`MAX_CONSECUTIVE_RECONNECTS`, **fixed at 5** in
[`AbstractMqttCallback`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/mqtt/AbstractMqttCallback.java),
[`AbstractPulsarCallback`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/pulsar/AbstractPulsarCallback.java),
[`KafkaClientV2`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/kafka/KafkaClientV2.java) and
[`CustomWebSocketClient`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/notification/websocket/CustomWebSocketClient.java).
After 5 attempts the message is acknowledged and dropped, so one undigestible payload
cannot block a subscription forever. This is not configurable per mapping.

For MQTT the redelivery is achieved by *reconnecting* (with exponential back-off), so the
broker retransmits whatever it has not seen acknowledged — which is why it is gated by the
connector property `reconnectOnProcessingError`. With that property off, the connector
simply leaves the message unacknowledged and waits for the broker to retransmit on its own.

Note the two counters are per different things: the poison-pill counter is **per message**
(and cleared as soon as that message succeeds), `currentFailureCount` is **per mapping**.

### Failure-handling gotchas

- **The two counters are driven from different places.** `currentFailureCount` is
  incremented by the *processor* that failed, for every failure. Whether the *message* is
  then redelivered is a separate decision made by the connector callback from the HTTP
  status class: `< 500` (client error, e.g. a malformed payload) is acknowledged —
  redelivering would not help — while `>= 500` triggers a redelivery. So a mapping can be
  deactivated by failures that were never retried.
- **`maxFailureCount` is not a rate.** 10 means "10 in a row", however long that takes. A
  mapping that alternates success/failure forever never trips it. That is intentional —
  use the `errors` counter and the *Monitoring* tab to spot those.
- **Deactivation is silent for the device.** The broker keeps publishing; the mapping is
  simply no longer subscribed. The `MAPPING_FAILURE_EVENT` and the mapping's inactive state
  in the UI are the only signals, so alert on that event if a mapping is business-critical.

---

## Tests

| Test | Covers |
|---|---|
| [`QosTest`](../../dynamic-mapper-service/src/test/java/dynamic/mapper/model/QosTest.java) | Levels, `max`/`min`/`orDefault`, `clampTo` (downgrade, upgrade, no-restriction), the JSON wire format, and the `Mapping.qos` default. |
| [`MappingSubscriptionManagerTest`](../../dynamic-mapper-service/src/test/java/dynamic/mapper/connector/core/client/MappingSubscriptionManagerTest.java) | Max-QoS-per-topic, QoS upgrade on add and on reconcile, reference counting. |
| [`AMQPClientTest`](../../dynamic-mapper-service/src/test/java/dynamic/mapper/connector/amqp/AMQPClientTest.java) | Clamping to a connector's capability, and `supportedQos` reaching the specification. |
| [`MQTT3ClientTest`](../../dynamic-mapper-service/src/test/java/dynamic/mapper/connector/mqtt/MQTT3ClientTest.java) | MQTT never clamps; `null` falls back to the default. |
| [`GooglePubSubClientTest`](../../dynamic-mapper-service/src/test/java/dynamic/mapper/connector/googlepubsub/GooglePubSubClientTest.java) | Ack-before-processing vs. ack-after-success / nack-on-error. |
| [`MappingStatusServiceFailureCountTest`](../../dynamic-mapper-service/src/test/java/dynamic/mapper/service/status/MappingStatusServiceFailureCountTest.java) | The streak semantics: threshold reported only on the failure that reaches it, `maxFailureCount == 0` never trips, success clears the streak. |
| [`MappingServiceFailureThresholdTest`](../../dynamic-mapper-service/src/test/java/dynamic/mapper/service/MappingServiceFailureThresholdTest.java) | The mapping is really deactivated at the threshold, and a burst of failures deactivates only once. |
