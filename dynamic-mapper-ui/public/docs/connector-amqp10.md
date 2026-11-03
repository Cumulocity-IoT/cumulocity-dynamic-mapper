---
title: AMQP 1.0 connector
---

### AMQP 1.0 connector {#amqp10-connector}

The **AMQP 1.0 Connector** connects to AMQP 1.0 brokers such as Azure Service Bus, ActiveMQ Artemis, and Solace. It uses the
Apache Qpid JMS client and supports inbound and outbound mappings with the QoS levels at most once and at least once. For
RabbitMQ and other AMQP 0-9-1 brokers, use the [AMQP 0-9-1 connector](/c8y-pkg-dynamic-mapper/introduction/connector-amqp).

#### Connection and authentication

| Property | Default | Description |
|---|---|---|
| **protocol** (required) | `amqp://` | `amqp://` is unencrypted. `amqps://` uses TLS. |
| **host** (required) | `localhost` | Host name or IP address of the broker. |
| **port** (required) | 5672 | Usually 5672 for `amqp://` and 5671 for `amqps://`. |
| **username**, **password** | empty | Credentials of the broker user. The password is a secret. |
| **Client ID** | empty | The client ID the connector uses at the broker. Empty lets the client library generate one. |

#### Destinations

| Property | Default | Description |
|---|---|---|
| **Destination type** (required) | Queue | Queue for point-to-point delivery. Topic for publish and subscribe. |
| **Content type** | empty | AMQP content type set on outbound messages, for example `application/json`. Empty does not set it. |
| **Automatic recovery** | On | Reconnects automatically after a connection loss. |

#### TLS

Shown when the protocol is `amqps://`.

| Property | Description |
|---|---|
| **Trust custom server certificate** (`trustCustomCertificate`) | Trust a broker certificate that is self-signed or issued by a private CA. Shown for `amqps://`. |
| **Trusted certificate name** and **Trusted certificate fingerprint** | Reference a certificate that you uploaded to **Device Management → Management → Trusted certificates**. The fingerprint is the SHA-1 fingerprint. |
| **Certificate chain (PEM)** | Paste the CA certificate chain inline instead. |

#### Topics and wildcards

**Wildcards in inbound topics** and **Wildcards in outbound topics** are both off by default. Both are editable.
