---
title: Connector Reference
---

This page walks through the connector configuration UI and covers connector-specific setup details. For the list
of supported connectors, directions, and payload formats, see the table in
[Managing connectors](/c8y-pkg-dynamic-mapper/introduction/managing-connectors#managing-connectors).

### Adding and managing connectors {#adding-and-managing-connectors}

Add a new connector with
[**Configuration → Connectors → Add connector**](/c8y-pkg-dynamic-mapper/node3/connectorConfiguration). The
configuration properties shown adapt to the selected connector type. Creating, editing, deleting, and connecting
connectors requires the `ROLE_DYNAMIC_MAPPER_ADMIN` role. See
[Managing permissions](/c8y-pkg-dynamic-mapper/introduction/access-control).

![Payload type](../../../resources/image/Dynamic_Mapper_Connector_New.png "Creating a new connector.")

The configured connectors are listed in a table with the columns **Name**, **Status**, **Identifier**, **Type**,
**Directions**, and **Enabled**. The row actions are edit, **Duplicate**, delete, and **Export connector**. A
connector must be disabled before you can edit it. The **Status** is one of `UNKNOWN`, `CONFIGURED`, `ENABLED`,
`CONNECTING`, `CONNECTED`, `DISCONNECTED`, `DISCONNECTING`, `FAILED`, or `RETRYING`. `RETRYING` means the connector
is connected, but a temporary platform error delays the start of its subscriptions, and the service retries
automatically. A mapping runs on a connector only while the connector is enabled and `CONNECTED`.

For connector types that support it (Webhook, Cumulocity API, and REST Polling), the **Test connection** button in
the connector form checks the configuration before you save it.

The **⋮** menu of the page also holds **Reconnect Subscription outbound**, **Export all connectors**, and
**Import connectors**. Exported files contain the value `****` instead of every password, token, and other secret.
An imported connector is created disabled, and the service drops masked secrets, so open the imported connector,
enter the secrets again, and then enable it. The Default HTTP Connector is neither exported nor imported.

![Connector overview](../../../resources/image/Dynamic_Mapper_Connector_Overview.png "The table of configured connectors.")

When a connection fails to establish, the **Details & logs** page of the connector (open it from the table) shows
the underlying error, which is often the fastest way to spot an incorrect parameter. The page records status
changes only while **Send connector lifecycle** is on under **Configuration → Service configuration → Monitoring**.
It is off by default. Otherwise, the page shows **Logs not updated: lifecycle logging disabled**.

![Connector logs](../../../resources/image/Dynamic_Mapper_Connector_Details.png "Connection logs helping identify why a connector failed to connect.")

### Connector pages {#connector-pages}

Each connector with connector-specific setup has its own page:

| Connector | What the page covers |
|---|---|
| [HTTP connector](/c8y-pkg-dynamic-mapper/introduction/connector-http) | The Default HTTP Connector, its endpoint, role, and response codes |
| [Webhook connector](/c8y-pkg-dynamic-mapper/introduction/connector-webhook) | Outbound calls, and the setting **Use Cumulocity API of this tenant** |
| [REST Polling connector](/c8y-pkg-dynamic-mapper/introduction/connector-rest-polling) | Poll interval, authentication, incremental fetch, and pagination |
| [Kafka connector](/c8y-pkg-dynamic-mapper/introduction/connector-kafka) | SASL authentication, TLS, and custom CA trust |
| [Pulsar connector](/c8y-pkg-dynamic-mapper/introduction/connector-pulsar) | Tenant, namespace, topic type, and how a mapping topic becomes a Pulsar topic |
| [Pulsar, Kafka, and MQTT compared](/c8y-pkg-dynamic-mapper/introduction/how-to-pulsar-kafka-mqtt) | How tenant, namespace, subscription, consumer group, and key terms correspond across the three brokers |
