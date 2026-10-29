# How Pulsar, Kafka, and MQTT concepts map to Dynamic Mapper

A mapping describes what to transform. The connector describes where the data lives. This page shows how the
terms of Apache Pulsar, Apache Kafka, and MQTT correspond to each other and to the Dynamic Mapper model, and which
connector property carries each Pulsar-specific detail.

The rule behind all of it: **a mapping contains only a logical, MQTT-style topic. Everything specific to a broker
(tenant, namespace, topic type, consumer group, subscription name, credentials) is connector configuration.**
Moving a mapping to another broker then means changing the connector, not the mapping.

## Concept map

| Dynamic Mapper | MQTT | Kafka | Pulsar |
|---|---|---|---|
| Connector instance | Connection to a broker | Connection to a cluster (`bootstrapServers`) | Connection to a service URL (`serviceUrl`) |
| Isolation boundary above the topic | None (the broker itself) | None (the cluster itself) | **Tenant**, then **namespace** |
| Topic in the mapping (`subscriptionTopic`, `publishTopic`) | Topic or topic filter, levels separated by `/` | Topic name | Topic name inside a namespace |
| Wildcards in an inbound mapping topic | `+` one level, `#` the rest | Reported as unsupported by the connector | Supported, translated to a Pulsar topic regex |
| Message key (`_CONTEXT_DATA_.key`, transport field `key`) | Not available | Record key | Message key |
| Who tracks what was consumed | Broker session (`cleanSession`) | Consumer group (`groupId`) and committed offsets | **Subscription** (`subscriptionName`) |
| Delivery guarantee (mapping QoS) | MQTT QoS 0, 1, 2 | Consumer commit behavior | Acknowledgement and subscription type |
| Parallelism | Not part of the protocol | Partitions in a consumer group | Subscription type (Shared, Key_Shared, ...) and partitioned topics |
| Durability of the topic | Retained messages, persistent session | Always log-based | **Persistent** or **non-persistent** topic |

## Pulsar terms in detail

A full Pulsar topic name has this form:

```
persistent://example-tenant/example-namespace/COMMON
└─ domain ┘  └── tenant ──┘ └─ namespace ───┘ └topic┘
```

| Pulsar term | Meaning | Closest equivalent | Dynamic Mapper setting |
|---|---|---|---|
| Tenant | Top-level owner, usually one per customer or team. Holds authorization and quota. | None in MQTT or Kafka. Often a broker-wide ACL prefix. | Connector property `pulsarTenant` |
| Namespace | Group of topics within a tenant. Policies (retention, permissions) apply here. | None. Closest: a Kafka topic-name prefix. | Connector property `pulsarNamespace` |
| Topic | Named stream of messages | MQTT topic, Kafka topic | **Mapping topic** (logical name only) |
| Persistent / non-persistent | Persistent topics store messages durably. Non-persistent topics keep them in memory and drop them if nobody is connected. | Kafka is always persistent. MQTT persistence depends on QoS and session. | Connector property `pulsarTopicDomain` |
| Subscription | A named cursor on a topic that records which messages a consumer group has acknowledged | Kafka consumer group, MQTT persistent session | Connector properties `subscriptionName`, `useSubscriptionNameAsIs` |
| Subscription type | How consumers that share a subscription split messages: Exclusive, Failover, Shared, Key_Shared | Kafka partition assignment in a consumer group | Connector property `subscriptionType` |
| Message key | Used for ordering and Key_Shared routing | Kafka record key | `_CONTEXT_DATA_.key` or transport field `key` |
| Partitioned topic | Topic split into internal partitions, named `topic-partition-N` | Kafka partitions | Transparent. The connector strips `-partition-N` when it resolves the topic. |

### Where the tenant, namespace, and topic type come from

The connector builds the full name from the connector configuration plus the mapping topic:

| Mapping topic | Connector configuration | Resulting Pulsar topic |
|---|---|---|
| `COMMON` | `pulsarTenant=example-tenant`, `pulsarNamespace=example-namespace` | `persistent://example-tenant/example-namespace/COMMON` |
| `COMMON` | `pulsarNamespace=example-tenant/example-namespace` (combined form) | `persistent://example-tenant/example-namespace/COMMON` |
| `COMMON` | as above, `pulsarTopicDomain=non-persistent` | `non-persistent://example-tenant/example-namespace/COMMON` |
| `devices/+/data` | `pulsarTenant=public`, `pulsarNamespace=default` | topics matching `persistent://public/default/devices-<one level>-data` |
| `persistent://t/n/COMMON` | any | used as written (tenant, namespace, and type settings are ignored) |

Defaults are tenant `public`, namespace `default`, and persistent.

Prefer the logical form (`COMMON`). A full `persistent://...` name in a mapping ties that mapping to one broker layout.
The combined `tenant/namespace` form in `pulsarNamespace` overrides `pulsarTenant`.

### Topic name conversion

MQTT topics use `/` as a level separator. A Pulsar topic name is a single segment, so the connector converts it:

| Step | Example |
|---|---|
| `/` becomes `-` | `devices/sensor1/data` becomes `devices-sensor1-data` |
| Spaces and characters outside `a-z A-Z 0-9 . _ -` become `_` | `my topic!` becomes `my_topic_` |
| Leading and trailing `.`, `_`, `-` are removed | `-abc-` becomes `abc` |
| Letter case is kept | `COMMON` stays `COMMON` |

Inbound, the connector reverses the conversion when it can verify the result against the mapping's topic filter, so
`_TOPIC_LEVEL_` and topic-based mapping resolution keep working with `devices/sensor1/data`. When it cannot verify
the match, the mapping sees the Pulsar local name (for example `COMMON`).

Because the conversion turns `/` into `-`, a topic level that itself contains `-` is ambiguous in wildcard
subscriptions. For Pulsar, prefer topic names that need no levels.

### Subscription naming

By default the subscription name is generated per topic:

```
<subscriptionName>-<topic with "/" replaced by "-", "+" by "wildcard", "#" by "multilevel">
```

Existing connectors keep this behavior, so their subscriptions and read positions stay as they are.

Enable **Use subscription name as is** when the broker only allows a pre-provisioned subscription name. The
connector then uses `subscriptionName` unchanged for every topic of that connector. This is valid because Pulsar
scopes a subscription to a topic: the same name on two topics creates two independent subscriptions.

### Subscription type and QoS

If `subscriptionType` is configured, it always wins. The connector property defaults to `Shared`. Only when the
property is empty does the mapping QoS decide:

| Mapping QoS | Acknowledgement | Subscription type if none configured |
|---|---|---|
| At most once | Acknowledged on receipt | Shared |
| At least once | Acknowledged after processing | Shared |
| Exactly once | Acknowledged after processing | Exclusive |

## Kafka and MQTT in the same terms

### Kafka

| Setting | Meaning |
|---|---|
| Mapping topic | Kafka topic name, used as written. There is no tenant or namespace. |
| `groupId` (required) | The consumer group. It plays the role of the Pulsar subscription. Committed offsets are kept per group. |
| Record key | Read from `_CONTEXT_DATA_.key` (JSONata) or transport field `key` (Smart Functions, Java extensions). Outbound, a string mapped to `_CONTEXT_DATA_.key` becomes the record key. |
| Partitions | Handled by Kafka and the consumer group. Not visible in the mapping. |

A Kafka topic has no namespace, so topic naming conventions (for example a customer prefix) are the usual
substitute. Put such a prefix in the mapping topic, since the connector has no namespace property.

### MQTT

| Setting | Meaning |
|---|---|
| Mapping topic | MQTT topic or filter. `+` matches one level, `#` the remaining levels. |
| QoS | Per mapping, 0, 1, or 2. |
| `cleanSession` | `true` discards the session when the client disconnects. `false` keeps subscriptions and queued messages on the broker. For MQTT 5 it maps to `cleanStart`. |
| Topic levels | Available in mappings as `_TOPIC_LEVEL_`. |

MQTT has no key, tenant, namespace, or subscription name. The session and client ID take the place of a subscription.

## Choosing the Pulsar settings: a checklist

Given a name such as `persistent://example-tenant/example-namespace/COMMON` and a subscription `example-subscription`:

1. Split the name into domain, tenant, namespace, and topic.
2. Set `pulsarTenant` and `pulsarNamespace` (or the combined `tenant/namespace` form) on the connector.
3. Leave `pulsarTopicDomain` on Persistent, or set Non-persistent if the broker says so.
4. Set `subscriptionName` and enable **Use subscription name as is** if the broker requires that exact name.
5. In the mapping, use only the topic: `COMMON`.
6. Use the key only through `_CONTEXT_DATA_.key` or the transport field `key`.

## Known limits

- Short topic names always resolve inside the single namespace configured on the connector. To read from two
  namespaces, create two connectors, or use a full `persistent://...` topic in one mapping.
- Letter case is significant. `COMMON` and `common` are different Pulsar topics.
- The Pulsar domain setting applies only to short topic names. A full topic name carries its own domain.

## Related

- [extensions.md](extensions.md): adding a custom connector
- [backend/conventions.md](backend/conventions.md): connector conventions
