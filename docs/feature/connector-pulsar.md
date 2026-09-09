# Connector: Apache Pulsar

The Pulsar connector lets Dynamic Mapper connect to an Apache Pulsar cluster for both
inbound and outbound mappings. Implemented by
[`PulsarConnectorClient`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/pulsar/PulsarConnectorClient.java)
using the official Apache Pulsar Java client (`org.apache.pulsar.client.api.*`), and
extends `AConnectorClient` — see [connector-framework.md](connector-framework.md) for
the shared abstraction. For Cumulocity's own managed MQTT Service, which is also built
on this Pulsar base class but with a very different topic model, see
[connector-mqtt-service.md](connector-mqtt-service.md) — this document covers the
generic/user-configured Pulsar connector only.

## Configuration (`ConnectorSpecification`)

Built via `ConnectorSpecificationBuilder.create("Apache Pulsar", ConnectorType.PULSAR)`:

| Property | Type | Required | Default | Notes |
|---|---|---|---|---|
| `serviceUrl` | string | yes | `pulsar://localhost:6650` | |
| `enableTls` | boolean | no | `false` | |
| `useSelfSignedCertificate` + cert fields | boolean / string | no | `false` | shown when `enableTls=true` |
| `authenticationMethod` | option | no | `none` | `none`/`token`/`oauth2`/`tls`/`basic` |
| `authenticationParams` | sensitive | no | — | shown for non-`none` methods |
| `connectionTimeoutSeconds` / `operationTimeoutSeconds` / `keepAliveIntervalSeconds` | numeric | yes | `30` each | |
| `subscriptionType` | option | no | `Shared` | `Exclusive`/`Shared`/`Failover`/`Key_Shared` |
| `subscriptionName` | string | no | — | |
| `supportsWildcardInTopicInbound` | boolean, readonly | no | `true` | |
| `supportsWildcardInTopicOutbound` | boolean, readonly | no | `false` | |
| `pulsarTenant` / `pulsarNamespace` | string | no | `public` / `default` | |
| `isSparkplugHost` / `sparkplugHostId` | boolean / string | no | `false` | see Sparkplug note below |

## Topic model

A mapping topic maps to `persistent://<tenant>/<namespace>/<topicName>` unless already
prefixed with `persistent://`/`non-persistent://`. MQTT-style topic segments are
sanitized (`/`→`-`, non-alphanumeric→`_`). Subscription name is
`<subscriptionName>-<sanitizedTopic>` (plus a test-mode suffix when applicable).
Subscription type per topic is taken from the `subscriptionType` config unless the
mapping's QoS is `EXACTLY_ONCE`, in which case it's forced to `Exclusive`; otherwise
`Shared`.

## Subscribe / unsubscribe

If a topic contains MQTT-style wildcards (`+`/`#`), `subscribe()` translates them into
a Pulsar regex and uses `.topicsPattern(...)`; otherwise a plain `.topic(...)`.
`acknowledgmentGroupTime` is set to 100ms when acking is required, `0` otherwise.
`unsubscribe()` closes and removes the consumer.

## Publish (`publishMEAO`)

Gets or creates a cached `Producer<byte[]>` per topic, with up to 3 creation retries;
handles a Pulsar PIP-344 `FeatureNotSupportedException` by falling back to
`createAsync()`. `AT_MOST_ONCE` uses `producer.sendAsync()` fire-and-forget (swallowing
failures); other QoS levels use blocking `producer.send()`.

## Ack / QoS

`supportedQOS = [AT_MOST_ONCE, AT_LEAST_ONCE, EXACTLY_ONCE]`. `QoSAwarePulsarCallback`
immediately acks `AT_MOST_ONCE` messages regardless of processing outcome. For
`AT_LEAST_ONCE`/`EXACTLY_ONCE`, ack/nack is deferred to the processing result: ack on an
HTTP-style result code below 500, `negativeAcknowledge()` at or above 500, with a
poison-pill discard after `MAX_CONSECUTIVE_FAILURES=5` consecutive failures.

## Housekeeping

`connectorSpecificHousekeeping()` prunes disconnected producers from the map so they're
lazily recreated on the next publish. A separate `monitorSubscriptions()` does the
analogous thing for consumers — but by outright closing and removing them, with **no
resubscribe logic**: a dropped consumer stays gone until some other trigger (e.g. a
full reconnect cycle via `initializeSubscriptionsAfterConnect()`) re-subscribes it.
This asymmetry (producers self-heal lazily, consumers don't) is worth knowing if
inbound messages silently stop arriving after a connection blip.

## TLS / auth

TLS via the `pulsar+ssl://` scheme, auto-adjusted from `pulsar://` when `enableTls=true`;
a self-signed cert is written to a temp PEM file and passed as
`tlsTrustCertsFilePath`. Auth methods: `token` → `AuthenticationFactory.token(...)`;
`oauth2` → `AuthenticationOAuth2` (constructed reflectively); `tls` → `AuthenticationTls`;
`basic` → `AuthenticationBasic`.

## Sparkplug B Host support — not actually implemented in this base class

The spec exposes `isSparkplugHost`/`sparkplugHostId`, and a `SparkplugCertificateManager`
is wired in, but the base class's default `createSparkplugPublisher()` just logs a
warning ("not implemented for this connector type") rather than actually publishing.
Only [`MQTTServicePulsarClient`](connector-mqtt-service.md) (the Cumulocity MQTT
Service subclass) overrides this to make Sparkplug Host mode functionally work. If you
enable Sparkplug Host mode on a plain, user-configured Pulsar connector, expect it to
be a no-op.

## Supported directions

`INBOUND` and `OUTBOUND` (inherited by the `MQTTServicePulsarClient` subclass too,
which does not override `supportedDirections()`).

## Gotchas

- Sparkplug Host mode on the base class is effectively decorative — see above.
- A dropped consumer is not automatically resubscribed by
  `connectorSpecificHousekeeping()`/`monitorSubscriptions()` — see the Housekeeping
  section.
- Git history flags `SubscriptionType.FAILOVER` as a fix for connection churn on
  certain subscription types (commit `169d23d1e` — "Adding Pulsar SubscriptionType.FAILOVER
  to prevent re-connection attempts") — if you see repeated reconnects, check which
  `subscriptionType` is configured.
