# Harmonizing acknowledgement handling across connectors

Status: analysis done 2026-11-01. Implementation started; see [Status](#status).

Follows [ack-after-pipeline-result.md](ack-after-pipeline-result.md), which fixed AMQP, the HTTP connector, and
Kafka acknowledging before the pipeline finished. That fix added one helper, but the connectors that already worked
kept their own copies. The result is not harmonized.

## Summary

After a message passes through the pipeline, every connector must decide: acknowledge (the message is done),
retry (the broker redelivers it), or drop. The decision depends on the pipeline result. Today that decision is
written out separately in seven places and the copies disagree.

```mermaid
flowchart TD
    R[pipeline result] --> M[MQTT callback]
    R --> P[Pulsar callback]
    R --> MS[MQTT Service Pulsar callback]
    R --> PS[Pub/Sub client]
    R --> K[Kafka client]
    R --> H[REST polling]
    R --> A[awaitAckable helper]
    A --> AM[AMQP 0-9-1 and 1.0]
    A --> HT[HTTP connector]
```

## Current state

| Connector | Logic | Waiting thread |
|---|---|---|
| MQTT (`AbstractMqttCallback`) | own copy | virtual thread |
| Pulsar (`PulsarCallback`) | own copy | virtual thread |
| Cumulocity MQTT Service (`MQTTServicePulsarCallback`) | own copy | virtual thread |
| Google Pub/Sub (`GooglePubSubClient`) | own copy | subscriber thread |
| Kafka (`KafkaClientV2`) | own copy | poll thread |
| REST polling (`HttpPollingConnector`) | own copy, decides whether the cursor advances | polling thread |
| AMQP 0-9-1 and 1.0 | `awaitAckable` | consumer thread |
| HTTP connector | `awaitAckable` | request thread |

### Where the copies disagree

| Aspect | MQTT | Pulsar | Pub/Sub | Kafka | AMQP | HTTP |
|---|---|---|---|---|---|---|
| Client error (4xx) | ack | ack | nack | ack | ack | 200 |
| Error without HTTP status | ack | ack | nack | ack | ack | 200 |
| Server error (5xx) | no ack, reconnect | nack | nack | seek back | nack, requeue | 500 |
| Checks the cancelled flag | yes | yes | no | no | yes | yes |
| Timeout grows with each retry | yes | no | no | no | no | no |
| Limit on repeated failures | 5 reconnects, only if `reconnectOnProcessingError` is on | 5, then drop | none | consumer restart after 10 errors | none | caller decides |

## Findings

| # | Priority | Finding |
|---|---|---|
| 1 | P1 | **Kafka and Pub/Sub ignore a cancelled pipeline.** A Smart Function stopped by the CPU limit completes the future early with the cancellation flag set. The other connectors refuse to acknowledge it. Kafka reads the cancelled leg as an error without an HTTP status and commits it. Pub/Sub nacks it only because it nacks every error. |
| 2 | P1 | **Pub/Sub nacks every error, including client errors.** A message Cumulocity rejects as invalid is redelivered until Pub/Sub's own dead-letter policy, if one is configured, removes it. The other connectors treat 4xx and errors without an HTTP status as handled. An existing test (`testProcessPubSubMessage_atLeastOnce_nacksOnProcessingError`) pins the Pub/Sub behavior, so it looks deliberate. This needs a decision, see below. |
| 3 | P2 | **No consistent poison-message rule.** Pulsar drops a message after 5 failures. MQTT reconnects up to 5 times if configured. AMQP requeues forever. Pub/Sub depends on external configuration. Kafka seeks back and, after the fix in the previous report, a permanently failing record loops until the consumer restart after 10 errors. |
| 4 | P2 | **Seven copies of the same logic.** A fix must be applied seven times, and the first fix (previous report) missed three connectors because they had no copy. |
| 5 | P3 | **No connector-level tests** for the AMQP callbacks, the HTTP connector acknowledgement, or Kafka's per-record decision. |
| 6 | P3 | **Different retry tuning.** Only MQTT lengthens the timeout on a retry. This is a design choice that nobody wrote down. |

## Decision needed: what is an error worth retrying?

MQTT, Pulsar, Kafka, AMQP, and HTTP all use one rule: retry only on a server-side (5xx) failure, a timeout, or a
cancellation. A mapping error without an HTTP status, such as a JavaScript exception, is acknowledged, because
redelivering a deterministic failure repeats it. Pub/Sub deviates and retries every error.

| Option | Effect |
|---|---|
| A. Pub/Sub follows the common rule | Consistent. A mapping bug no longer loops. A failing message is dropped after one attempt and recorded as an error in the mapping status. |
| B. Keep Pub/Sub as is and document it | Messages are retried until Pub/Sub's dead-letter policy stops them. Needs a dead-letter policy on the subscription, and a mapping bug can loop. |
| C. Change the common rule to retry every error, with a failure limit | Treats mapping bugs as retryable, which only helps with transient causes. Needs the shared poison-message rule first. |

Recommendation: A, together with the shared poison-message rule (step 3 below), then revisit C. Until you decide,
Pub/Sub keeps its current error rule and gains only the cancelled check.

## Proposed work

1. **Fix the clear bugs now.** Kafka uses the shared helper (same rule, plus the cancelled check). Pub/Sub gets the
   cancelled check. Add tests for both, and for the AMQP callbacks.
2. **Migrate the remaining copies to one policy method** that returns `ACK` or `RETRY` from the pipeline result,
   including the cancelled check and the 4xx rule. Each connector only translates the decision into its broker call
   and keeps its own extras (MQTT timeout growth, Pulsar poison-pill, REST polling cursor).
3. **One poison-message rule:** a per-message failure counter with a shared limit and a dead-letter target.
4. **Contract test:** one parameterized test that every connector must pass: a failed pipeline never leads to an
   acknowledgement, and neither does a cancelled one.
5. **Decide the Pub/Sub error rule** (options above).

## Decisions (2026-11-01)

| Question | Decision |
|---|---|
| Pub/Sub error rule | Follow the common rule: ack client errors and mapping errors without an HTTP status, nack only on a server error, timeout, cancellation, or exception |
| Failed message | Drop after N attempts and keep the error in the mapping status; N is `maxProcessingAttempts`, **default 3** |
| Migration scope | All broker callbacks except REST polling |

## Status

| Step | State |
|---|---|
| 1. Kafka on the helper, Pub/Sub cancelled check, tests | Done |
| 2. One policy method | Done for MQTT, Pulsar, Cumulocity MQTT Service, Pub/Sub, Kafka, AMQP, and the HTTP connector (`ProcessingResultHelper.awaitOutcome`). Not migrated: REST polling (by decision) and `CustomWebSocketClient` (outbound notifications, own limit of 5) |
| 3. One poison-message rule | Done: `MessageFailureTracker` and the setting `maxProcessingAttempts` (default 3) in the service configuration, the UI (Processing tab), and the docs. Not covered: AMQP 1.0 (see below), REST polling, `CustomWebSocketClient` |
| 4. Contract test | Partly: per-connector tests for AMQP, Kafka, Pub/Sub, and the HTTP controller; unit tests for the helper and the tracker. No single parameterized test over all connectors yet |
| 5. Pub/Sub error rule | Done (decision above) |

### What changed

- **`ProcessingResultHelper.awaitOutcome`** returns `ACK` or `RETRY` and accepts a wait-time override, which MQTT and
  the Cumulocity MQTT Service use to lengthen the wait on a retransmission. `awaitAckable` calls it.
- **`MessageFailureTracker`** counts failures per message, bounded in memory, with a limit read from the service
  configuration on every failure. Keys survive redelivery: Pub/Sub message id, Kafka topic-partition-offset, AMQP
  message id or routing key and body hash, MQTT and Pulsar as before.
- **Behavior changes to be aware of:**
  - Pub/Sub no longer redelivers client errors and mapping errors without an HTTP status.
  - The default limit is 3 instead of 5 for MQTT and Pulsar (it was 5), so a failing message is dropped sooner.
  - MQTT now counts and drops after the limit also when `reconnectOnProcessingError` is off. Before, nothing
    bounded the retransmissions in that case.
  - AMQP 0-9-1 and Kafka now drop a message after the limit. Before, nothing did.
- **Bug found on the way and fixed:** `HttpConnectorController` did not compile after the previous change, because
  `HttpClient.onMessage` returned `void`. See [the note below](#note-on-the-earlier-build).

### Still open

- **AMQP 1.0:** an unacknowledged message is redelivered only when the session restarts, so the limit cannot count.
  The JMS specification also says `acknowledge()` acknowledges every message the session has consumed, which would let a
  later success acknowledge an earlier failed message. Needs a test against the Qpid client before changing.
- **REST polling and `CustomWebSocketClient`** keep their own copies and limits.
- **One parameterized contract test** that every connector must pass.

### Note on the earlier build

The Maven runs after the first acknowledgement commit reported "Nothing to compile - all classes are up to date",
because the IDE had already compiled the classes. A compile error in `HttpConnectorController` was therefore hidden:
the HTTP connector endpoint would have failed at run time. It was found when a configuration change forced a real
compile. All results in this report come from `mvn clean test`, which compiles everything with `javac`.
