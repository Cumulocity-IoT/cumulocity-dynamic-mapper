---
title: Cumulocity MQTT Service connector
---

### Cumulocity MQTT Service connector {#c8y-mqtt-service-connector}

The **Cumulocity MQTT-Service** connector connects the mapper to the Cumulocity MQTT Service of the same tenant. It supports
inbound and outbound mappings. You can create only one instance per tenant.

The connection is preconfigured. The service fills in the service URL, the authentication, the timeouts, and the Pulsar
tenant and namespace from the microservice credentials when the connector connects. These values are hidden and read-only,
so creating a connector needs only a type, an identifier, and a name.

MQTT Service supports the QoS levels at most once and at least once. Exactly once is reduced to at least once. The connector
always processes inbound messages at least once, whatever QoS the mapping has.

| Property | Default | Description |
|---|---|---|
| **Redelivery delay (s)** | 60 | Seconds before a message that the mapper rejected is delivered again. |
| **Sparkplug host** | Off | Publishes Birth and Death certificates when the connector connects and disconnects. |
| **Sparkplug host ID** | tenant name | The host ID used in those certificates. Shown only when **Sparkplug host** is on. |
| **Wildcards in inbound topics** | On, read-only | `+` and `#` are allowed in inbound mapping topics. |
| **Wildcards in outbound topics** | On, read-only | `+` and `#` are allowed in outbound publish topics. |

Cumulocity MQTT Service supports Sparkplug B only partially, because it does not provide retained messages. See
[SparkPlug B](/c8y-pkg-dynamic-mapper/introduction/sparkplugb).
