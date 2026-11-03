---
title: Managing connectors
---

### Managing connectors {#managing-connectors}

The first step when working with the Dynamic Mapper is to create a connector. Select based on your message broker
or integration requirements.
For IoT devices, MQTT connectors are most common. For enterprise integrations, consider Kafka or HTTP connectors.
Webhooks are ideal for outbound integrations to external systems, REST Polling is for inbound integrations
with systems that only expose a REST API and cannot push data themselves, and the Cumulocity API connector is
for internal Cumulocity REST API operations.
The mapper supports the following connectors:

| Connector | Inbound | Outbound |
|---|:---:|:---:|
| [**AMQP Connector**](/c8y-pkg-dynamic-mapper/introduction/connector-amqp) (AMQP 0-9-1, RabbitMQ, etc.) | ✓ | ✓ |
| [**AMQP 1.0 Connector**](/c8y-pkg-dynamic-mapper/introduction/connector-amqp10) (Azure Service Bus, Artemis, Solace, etc.) | ✓ | ✓ |
| [**Apache Pulsar**](/c8y-pkg-dynamic-mapper/introduction/connector-pulsar) | ✓ | ✓ |
| [**Cumulocity API**](/c8y-pkg-dynamic-mapper/introduction/connector-cumulocity-api) (for internal Cumulocity REST API) | – | ✓ |
| [**Cumulocity MQTT-Service**](/c8y-pkg-dynamic-mapper/introduction/connector-c8y-mqtt-service) (device isolation, one instance per tenant) | ✓ | ✓ |
| [**Google Cloud Pub/Sub**](/c8y-pkg-dynamic-mapper/introduction/connector-pubsub) (e.g. Google Manufacturing Data Engine) | ✓ | ✓ |
| [**HTTP Endpoint**](/c8y-pkg-dynamic-mapper/introduction/connector-http) (the Default HTTP Connector, one instance per tenant) | ✓ | – |
| [**Kafka**](/c8y-pkg-dynamic-mapper/introduction/connector-kafka) | ✓ | ✓ |
| [**MQTT**](/c8y-pkg-dynamic-mapper/introduction/connector-mqtt) (MQTT 3.1.1) and **MQTT 5.0** | ✓ | ✓ |
| [**REST Polling**](/c8y-pkg-dynamic-mapper/introduction/connector-rest-polling) (for external REST APIs with no push capability) | ✓ | – |
| [**Webhook**](/c8y-pkg-dynamic-mapper/introduction/connector-webhook) (for external REST APIs) | – | ✓ |

**Payload format is a per-mapping choice, not a connector restriction**: every connector above
carries raw message bytes into the same pipeline, and the mapping's own configured
[**payload type**](/c8y-pkg-dynamic-mapper/introduction/payload-types) (JSON, Flat File, Hex, Any
Payload) decides how those bytes are read, independent of which connector delivered them.

**SparkPlug B** is the one exception: it depends on an MQTT-specific protocol convention (retained
birth messages and the `spBv1.0/...` topic structure). It requires an **MQTT** connector; **Cumulocity MQTT-Service** supports it only
partially (retained messages are not available there). See
[**SparkPlug B**](/c8y-pkg-dynamic-mapper/introduction/sparkplugb) for details.

:::caution
Some connectors, such as the HTTP Endpoint and Cumulocity MQTT-Service, have only one instance per tenant. Multiple
mappings can share the same connector instance. Configure the connector properties carefully as they affect all
associated mappings.
:::

:::info Default HTTP Connector
The **Default HTTP Connector** does not need to be created manually. The service creates it automatically for
every tenant at microservice startup. The sender needs the `ROLE_DYNAMIC_MAPPER_HTTP_CONNECTOR_CREATE` role. The
endpoint is reachable at
`https://<YOUR_CUMULOCITY_TENANT>/service/dynamic-mapper-service/httpConnector/<MAPPING_TOPIC>`: the path segment
after `.../httpConnector/` is used directly as the mapping topic. For example, a JSON payload POSTed to
`.../httpConnector/temp/berlin_01` is resolved against a mapping with mapping topic `temp/berlin_01`.
:::

Add a new connector with
[**Configuration → Connectors → Add connector**](/c8y-pkg-dynamic-mapper/node3/connectorConfiguration). The
configuration properties shown adapt to the selected connector type.

A new connector starts disabled. Switch on **Enabled** in the connector table to connect it. Then deploy each mapping
to the connectors it should run on, because a mapping that is not deployed to a connector never runs there. See
[Troubleshooting](/c8y-pkg-dynamic-mapper/introduction/troubleshooting).

For screenshots of the connector form and the connector and connection-log tables, see the
[**Connector Reference**](/c8y-pkg-dynamic-mapper/introduction/connectors) page. It links to a page for each connector with
specific setup: HTTP, Webhook, REST Polling, Kafka, and Pulsar.

