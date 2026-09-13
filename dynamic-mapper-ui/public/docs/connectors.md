---
title: Connector Reference
---

This page walks through the connector configuration UI and covers connector-specific setup details. For the list
of supported connectors, directions, and payload formats, see the table in
[Managing connectors](/c8y-pkg-dynamic-mapper/introduction/managing-connectors#managing-connectors).

### Default HTTP Connector

The **Default HTTP Connector** does not need to be created manually — it is created automatically for every
tenant at microservice startup. It is reachable at
`https://<YOUR_CUMULOCITY_TENANT>/service/dynamic-mapper-service/httpConnector/<MAPPING_TOPIC>`: the path segment
after `.../httpConnector/` is used directly as the mapping topic. For example, a JSON payload POSTed to
`.../httpConnector/temp/berlin_01` is resolved against a mapping with mapping topic `temp/berlin_01`.

![HTTP connector settings](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Connector_Http.png "Default HTTP Connector (inbound) configuration properties.")

### Adding and managing connectors

Add a new connector using the following wizard
[**Configuration → Connectors → Add connector**](/c8y-pkg-dynamic-mapper/node3/connectorConfiguration). The
configuration properties shown are dynamically adapted to the selected connector type.

![Payload type](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Connector_New.png "Creating a new connector.")

The configured connectors are listed in a table that can be deleted, enabled/disabled, or updated/copied:

![Connector overview](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Connector_Overview.png "The table of configured connectors.")

When a connection fails to establish, the connection logs on the same page show the underlying error, which is
often the fastest way to spot an incorrect parameter:

![Connector logs](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Connector_Details.png "Connection logs helping identify why a connector failed to connect.")

### Webhook connector

The **Webhook** connector has a setting **Cumulocity Internal** which can be used when Cumulocity MEA should be
processed and sent back to Cumulocity Core as transformed MEA, e.g. receive an `EVENT` of type `c8y_Uplink` and use
a **SMART_FUNCTION** to decode the payload and transform it into a `MEASUREMENT`.

![Webhook connector settings](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Connector_WebHook.png "Webhook connector configuration properties.")

### Kafka connector security {#kafka-connector-security}

![Kafka connector settings](/apps/c8y-pkg-dynamic-mapper/image/Dynamic_Mapper_Connector_Kafka.png "Kafka connector configuration properties.")

The Kafka connector derives its `security.protocol` automatically — you do not set it directly:

| Credentials set? | Custom CA trusted? | Resulting protocol |
|:---:|:---:|---|
| yes | either | `SASL_SSL` |
| no | yes | `SSL` (TLS only, no authentication) |
| no | no | `PLAINTEXT` |

**Authentication.** Set **Username** and **Password**, then pick the **SASL mechanism** your broker offers:
`SCRAM-SHA-256`, `SCRAM-SHA-512`, or `PLAIN`. Use `PLAIN` for brokers that authenticate with an API key/secret
pair — Confluent Cloud, for example, supports only `PLAIN` and `OAUTHBEARER`, so a SCRAM mechanism there fails with
`UnsupportedSaslMechanismException`.

**Trusting a self-signed or internal CA.** Public-CA brokers (Confluent Cloud, most managed services) need no extra
configuration — the JVM's default truststore already trusts them. For a broker presenting a certificate from an
internal or self-signed CA, enable **Use Self Signed Certificate** and supply the CA one of two ways, exactly as for
the MQTT, AMQP and Pulsar connectors:

| Property | Description |
|---|---|
| `useSelfSignedCertificate` | Enables custom CA trust. The remaining properties below only appear once it is on. |
| `nameCertificate` + `fingerprintSelfSignedCertificate` | Reference a certificate already uploaded to the Cumulocity trusted-certificate store (**Device Management → Management → Trusted certificates**). |
| `certificateChainInPemFormat` | Paste the CA certificate chain inline in PEM format, as an alternative to the certificate store. |
| `disableHostnameValidation` | Skips TLS hostname verification. **Insecure — development and testing only.** |

:::caution
Client-certificate authentication (mTLS) is not supported: the Cumulocity certificate store holds trust material
only, not private keys. The same limitation applies to every other connector type.
:::

Mechanisms beyond the three listed above — `OAUTHBEARER`, `GSSAPI`/Kerberos, AWS MSK IAM — have no dedicated
fields, but can still be configured by setting the raw Kafka client properties (`sasl.mechanism`,
`sasl.jaas.config`, `security.protocol`, …) in **Default properties producer** and **Default properties consumer**.
Those maps are applied last and therefore override anything derived above.
