# Connector: Google Cloud Pub/Sub

The Google Pub/Sub connector lets Dynamic Mapper publish to and consume from Google
Cloud Pub/Sub topics/subscriptions, for both inbound and outbound mappings.
Implemented by
[`GooglePubSubClient`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/googlepubsub/GooglePubSubClient.java)
using the official `google-cloud-pubsub` Java client library, and extends
`AConnectorClient` — see [connector-framework.md](connector-framework.md) for the
shared abstraction. This is one of the newer connectors in the codebase (added as an
outbound-only sink, later extended to bidirectional support), so expect the
implementation to still be settling relative to the more established connectors.

---

## Requirements

**What it is for.** Connecting a tenant to Google Cloud Pub/Sub in both directions: consuming from
a subscription into mappings, and publishing mapping output to a topic.

- **Both directions.** Inbound requires a subscription ID; outbound requires a topic.
- **Authentication is a service-account key**, supplied as connector configuration. No other
  scheme is offered.
- **No wildcards.** Pub/Sub has no topic-pattern subscription, so a mapping topic must name its
  subscription exactly — patterns that work on MQTT do not translate.
- **Delivery is at-most-once or at-least-once**, chosen per mapping: at-most-once acknowledges
  before processing, at-least-once acknowledges only after the pipeline succeeded and negatively
  acknowledges on failure so Pub/Sub redelivers. Exactly-once is not offered — see
  [reliability.md](reliability.md).

---

## Implementation

### Configuration (`ConnectorSpecification`)

Built via `ConnectorSpecificationBuilder.create("Google Cloud Pub/Sub", ConnectorType.GOOGLE_PUBSUB)`:

| Property | Type | Required | Default | Notes |
|---|---|---|---|---|
| `projectId` | string | yes | — | Google Cloud project owning the topic/subscription |
| `topicId` | string | yes | `input-messages` | a mapping's own publish topic overrides this per request |
| `subscriptionId` | string | no | — | pre-existing Pub/Sub subscription to consume from; required for inbound mappings |
| `authMode` | option | yes | `serviceAccountKey` | `serviceAccountKey` or `applicationDefaultCredentials` (ADC) |
| `serviceAccountKey` | sensitive large text | yes | — | full JSON key of the service account, shown when `authMode=serviceAccountKey` |
| `adcCredentialsJson` | sensitive large text | no | — | contents of the ADC JSON file (e.g. `~/.config/gcloud/application_default_credentials.json`), shown when `authMode=applicationDefaultCredentials` |
| `publishTimeoutSeconds` | numeric | yes | `30` | how long to wait for a publish ack before treating it as failed |

The service account needs `roles/pubsub.subscriber` for inbound and
`roles/pubsub.publisher` for outbound.

### Credentials

Both auth modes are **pasted-JSON-string** based, not the client library's automatic
file-discovery ADC mechanism — `GoogleCredentials.fromStream(...)` is built directly
from the configured JSON string (service-account key or ADC file contents), wrapped in
a `FixedCredentialsProvider`.

### Publisher / subscriber lifecycle

Publishers and subscribers are cached per topic in concurrent maps. There's no
persistent socket managed directly — the Pub/Sub client library manages gRPC channels
per topic, lazily, on first publish. Publishers are created lazily on first publish,
with 3 retries (1s delay) for *publisher creation* only — not for the publish call
itself. Subscribers are created in `subscribe()` and started with
`subscriber.startAsync().awaitRunning()`, using asynchronous streaming pull (a
`MessageReceiver` callback), not synchronous `pull()` RPCs.

### Ack / nack model

- `AT_MOST_ONCE`: the consumer acks immediately, before dispatching to the processing
  pipeline — fire-and-forget.
- Anything above `AT_MOST_ONCE`: ack only after the pipeline processing result is
  known — nack (triggering redelivery) if the processing context reports an error, or
  on any exception (including a restored-interrupt on `InterruptedException`); ack
  otherwise.
- No explicit ack-deadline configuration is set on the subscriber builder — it uses the
  library's default deadline.

### Publish (`publishMEAO`)

Topic resolution: the mapping's own publish topic takes precedence, falling back to
the connector's configured default `topicId`. Message body is raw bytes (binary
payload if present, else the request body as UTF-8). Every message gets attributes
`sourceSystem=cumulocity` and, when derivable, `messageType=<API type>` — this is Pub/Sub
message-attribute metadata, not an ordering key; no ordering-key support (`setOrderingKey()`)
is implemented. Publish is blocked on with the configured `publishTimeoutSeconds` — so
unlike the WebHook connector, there is a configurable publish timeout — but there's
still no retry of the publish call itself if it fails (only publisher *creation*
retries, see above). The publish loop honors cooperative cancellation (checks
`Thread.currentThread().isInterrupted()` each iteration), e.g. for a pipeline timeout.

### Supported directions and wildcards

`INBOUND` and `OUTBOUND`. `supportsWildcardInTopic()` always returns `false` for both
directions — Pub/Sub topic names are plain identifiers with no MQTT-style wildcard
concept.

### Connection health

`isPhysicallyConnected()` is an approximation, since there's no single socket to probe:
it checks that credentials are set and that all currently-tracked subscribers report
`isRunning()`. `connectorSpecificHousekeeping()` is an explicit no-op — the client
library exposes no per-publisher health signal to prune on; a failed publisher is
simply recreated on demand on the next publish attempt. Disconnect budgets publisher
and subscriber teardown to 3s each, kept under the connector framework's overall 5s
disconnect ceiling.

### Gotchas

- A `PUBLISHER_RETRY_OVERHEAD_SECONDS` constant is documented in a comment as capping
  the library's internal 600s default retry timeout to `publishTimeoutSeconds + 5s`, so
  `awaitTermination()` during disconnect returns promptly — but its actual application
  point (a `RetrySettings` builder call) wasn't visible in the reviewed code path. Treat
  this as possibly aspirational/partially wired rather than confirmed-active behavior,
  and verify directly in `GooglePubSubClient.java` before relying on it.
- Being a newer connector, recent git history is dominated by test fixes and
  code-review-driven corrections (e.g. "Fixing Google Pub/Sub Tests", "Adding ADS for
  Google Pubsub, fixing related issues") rather than settled, long-stable behavior —
  double-check current code rather than assuming parity with the other, more mature
  connectors.
