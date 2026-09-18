---
title: Managing connectors
---

### Managing connectors {#managing-connectors}

The first step when working with the Dynamic Mapper is to create a connector. Select based on your message broker
or integration requirements.
For IoT devices, MQTT connectors are most common. For enterprise integrations, consider Kafka or HTTP connectors.
Webhooks are ideal for outbound integrations to external systems, while the Cumulocity API connector is for
internal Cumulocity REST API operations.
The mapper supports the following connectors and payload formats:

| Connector | Inbound | Outbound | JavaScript | Supported Payload Formats |
|---|:---:|:---:|:---:|---|
| **AMQP 0-9-1** (RabbitMQ, etc.) | ✓ | ✓ | ✓ | JSON, Hex, Protobuf, Extension |
| **AMQP 1.0** (Azure Service Bus, Artemis, Solace, etc.) | ✓ | ✓ | ✓ | JSON, Hex, Protobuf, Extension |
| **Apache Pulsar** | ✓ | ✓ | ✓ | JSON, Hex, Protobuf, Extension |
| **Cumulocity API** (for internal Cumulocity REST API) | – | ✓ | ✓ | JSON |
| **Cumulocity MQTT Service** (device isolation, one instance per tenant) | ✓ | ✓ | ✓ | JSON, Hex, Protobuf, Extension |
| **Google Cloud Pub/Sub** (e.g. Google Manufacturing Data Engine) | ✓ | ✓ | ✓ | JSON, Hex, Protobuf, Extension |
| **HTTP Connector** (one instance per tenant) | ✓ | – | ✓ | JSON, Hex, Protobuf, Extension |
| **Kafka** | ✓ | ✓ | ✓ | JSON, Hex, Protobuf, Extension |
| **MQTT** | ✓ | ✓ | ✓ | JSON, Hex, Protobuf, Extension, **SparkPlug B** |
| **Webhook** (for external REST APIs) | – | ✓ | ✓ | JSON |

:::caution
Some connectors like HTTP Connector and Cumulocity MQTT Service have only one instance per tenant. Multiple
mappings can share the same connector instance. Configure the connector properties carefully as they affect all
associated mappings.
:::

:::info Default HTTP Connector
The **Default HTTP Connector** does not need to be created manually — it is created automatically for every
tenant at microservice startup. It is reachable at
`https://<YOUR_CUMULOCITY_TENANT>/service/dynamic-mapper-service/httpConnector/<MAPPING_TOPIC>`: the path segment
after `.../httpConnector/` is used directly as the mapping topic. For example, a JSON payload POSTed to
`.../httpConnector/temp/berlin_01` is resolved against a mapping with mapping topic `temp/berlin_01`.
:::

Add a new connector using the following wizard
[**Configuration → Connectors → Add connector**](/c8y-pkg-dynamic-mapper/node3/connectorConfiguration). The
configuration properties shown are dynamically adapted to the selected connector type.

For screenshots of the connector wizard, the connector and connection-log tables, Webhook's **Cumulocity Internal**
setting, and Kafka's security/TLS configuration, see the
[**Connector Reference**](/c8y-pkg-dynamic-mapper/introduction/connectors) page.

