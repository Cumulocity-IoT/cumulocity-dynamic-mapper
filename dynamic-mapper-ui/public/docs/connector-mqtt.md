---
title: MQTT connector
---

### MQTT connector {#mqtt-connector}

The **MQTT** connector connects to an external MQTT broker over TCP or WebSocket. It supports inbound and outbound
mappings, MQTT 3.1.1 and MQTT 5.0, and Sparkplug B hosting. The connector type is the same for both versions. Choose the
version in **MQTT version**.

#### Connection and authentication

| Property | Description |
|---|---|
| **MQTT version** (required) | `3.1.1` (default) or `5.0`. |
| **protocol** (required) | `mqtt://` (default, plain TCP), `mqtts://` (TCP with TLS), `ws://` (WebSocket), or `wss://` (WebSocket with TLS). |
| **Host**, **Port** (required) | Address of the broker, for example port 1883 for `mqtt://` or 8883 for `mqtts://`. |
| **Server path** | Path of the WebSocket endpoint, for example `/mqtt`. Shown only for `ws://` and `wss://`. |
| **user**, **password** | Credentials of the broker user. Leave empty for anonymous access. The password is a secret. |
| **Client ID** (required) | The ID the connector uses at the broker. A broker allows one connection per client ID, so use a unique value. |

#### TLS

For `mqtts://` and `wss://`, the TLS properties trust a broker certificate from a private or self-signed CA. They work as
described in [Kafka connector security](/c8y-pkg-dynamic-mapper/introduction/connector-kafka#kafka-connector-security):

| Property | Description |
|---|---|
| **Trust custom server certificate** (`trustCustomCertificate`) | Trust a broker certificate that is self-signed or issued by a private CA. Shown for `mqtts://` and `wss://`. |
| **Trusted certificate name** and **Trusted certificate fingerprint** | Reference a certificate that you uploaded to **Device Management → Management → Trusted certificates**. The fingerprint is the SHA-1 fingerprint. |
| **Certificate chain (PEM)** | Paste the CA certificate chain inline instead. |
| **Disable hostname validation** | Skips the check that the certificate matches the host name. **Insecure. Use only for development and testing.** |

#### Session and reliability

| Property | Default | Description |
|---|---|---|
| **Clean session** (`cleanSession`) | On | On discards the session state when the connector disconnects. Off keeps subscriptions and queued QoS 1 and 2 messages on the broker while the connector is offline. For MQTT 5 this maps to `cleanStart`. |
| **Reconnect on processing error** | Off | Reconnects to the broker on timeouts or internal processing errors. Use it for QoS 1 and 2 with brokers that do not retransmit unacknowledged messages. Shown only when **Clean session** is off. |

#### Topics and wildcards

The mapping topic is an MQTT topic or filter. **Wildcards in inbound topics** and **Wildcards in outbound topics** are both on
by default. `+` matches one level and `#` matches the remaining levels. Topic levels are available in mappings as
`_TOPIC_LEVEL_`. The QoS (0, 1, or 2) is set per mapping. See
[Reliability settings](/c8y-pkg-dynamic-mapper/introduction/reliability-settings).

#### Sparkplug B host

| Property | Description |
|---|---|
| **Sparkplug host** (`isSparkplugHost`) | Publishes Birth and Death certificates when the connector connects and disconnects. |
| **Sparkplug host ID** | The host ID used in those certificates. Shown only when **Sparkplug host** is on. If you leave it empty, the tenant name is used. |

See [SparkPlug B](/c8y-pkg-dynamic-mapper/introduction/sparkplugb) for the protocol details.
