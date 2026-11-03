---
title: AMQP 0-9-1 connector
---

### AMQP 0-9-1 connector {#amqp-connector}

The **AMQP Connector** connects to AMQP 0-9-1 brokers such as RabbitMQ. It supports inbound and outbound mappings with
the QoS levels at most once and at least once. For AMQP 1.0 brokers, use the
[AMQP 1.0 connector](/c8y-pkg-dynamic-mapper/introduction/connector-amqp10).

#### Connection and authentication

| Property | Default | Description |
|---|---|---|
| **protocol** (required) | `amqp://` | `amqp://` is unencrypted. `amqps://` uses TLS. |
| **host** (required) | `localhost` | Host name or IP address of the broker. |
| **port** (required) | 5672 | Usually 5672 for `amqp://` and 5671 for `amqps://`. |
| **Virtual host** | `/` | Virtual host on the broker. |
| **username**, **password** | `guest`, empty | Credentials of the broker user. The password is a secret. |

#### Exchange and queues

| Property | Default | Description |
|---|---|---|
| **exchange** | empty | The exchange that inbound queues bind to and that outbound messages are published to. Empty uses the default exchange. |
| **Exchange type** | Topic | Type used when the connector declares the exchange: Topic, Direct, Fanout, or Headers. Use Topic to route by the mapping topic. |
| **Queue prefix** | empty | Prefix of the generated queue names. A queue is named `<prefix>.<topic>`, with `/` in the topic replaced by a dot. |
| **Auto-delete queues** | Off | Deletes a queue when its last consumer disconnects. |
| **Automatic recovery** | On | Reconnects and restores queues and bindings after a connection loss. |

#### TLS

Shown when the protocol is `amqps://`.

| Property | Description |
|---|---|
| **Trust custom server certificate** (`trustCustomCertificate`) | Trust a broker certificate that is self-signed or issued by a private CA. Shown for `amqps://`. |
| **Trusted certificate name** and **Trusted certificate fingerprint** | Reference a certificate that you uploaded to **Device Management → Management → Trusted certificates**. The fingerprint is the SHA-1 fingerprint. |
| **Certificate chain (PEM)** | Paste the CA certificate chain inline instead. |

#### Topics and wildcards

The routing key is the mapping topic with `/` replaced by a dot, for example `devices/sensor1` becomes `devices.sensor1`. **Wildcards in inbound topics** is on by default. **Wildcards in outbound
topics** is off by default. Both are editable.
