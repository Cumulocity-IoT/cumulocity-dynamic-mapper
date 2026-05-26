# Architecture

The solution is composed of two major components:

- A **microservice** - exposes REST endpoints, provides a generic connector interface which can be used by broker clients to
  connect to a message broker, a generic data mapper, a comprehensive expression language for data mapping and the
  [Cumulocity Microservice SDK](https://cumulocity.com/guides/microservice-sdk/introduction/) to connect to Cumulocity. It also supports multi tenancy.

- A **frontend (plugin)** - uses the exposed endpoints of the microservice to configure a broker connection & to perform
  graphical or code-based data mappings within the Cumulocity IoT UI.

The architecture of the components consists of the following components:

<p align="center">
<img src="resources/image/Dynamic_Mapper_Diagram_Architecture.png"  style="width: 100%;" />
</p>
<br/>
The main components of this project are:

- Default connectors for..
  - **MQTT 3.1.1 client** - using [hivemq-mqtt-client](https://github.com/hivemq/hivemq-mqtt-client) to connect and subscribe to MQTT brokers (MQTT 3.1.1)
  - **MQTT 5.0 client** - using hivemq-mqtt-client with MQTT 5.0 enhanced features (properties, subscriptions options)
  - **MQTT Service client** - using hivemq-mqtt-client to connect to Cumulocity MQTT Service (deprecated)
  - **Kafka connector** - to connect to Kafka brokers
  - **HTTP/REST endpoint** - to receive data from HTTP/REST clients
  - **AMQP connector** - supports AMQP 0.9.x and AMQP 1.0 brokers (e.g. RabbitMQ, Azure Service Bus)
  - **Apache Pulsar connector** - connects to Pulsar brokers (native Pulsar protocol and MQTT-over-Pulsar)
  - **Webhook connector** - exposes an HTTP endpoint that accepts inbound push payloads
- **Data mapper** - handling of received messages via connector and mapping them to a target data format for Cumulocity.
  Supports multiple payload formats and transformation types (see below).
- **C8Y client** - implements part of the Cumulocity REST API to integrate data
- **REST endpoints** - custom endpoints which are used by the Dynamic Mapper Frontend or can be used to add mappings programmatically
- **Mapper frontend** - A plugin for Cumulocity to provide a full blown UI to configure the Dynamic Mapper, including
  - Connector configuration
  - Mapping configuration (stepper wizard and unified editor)
  - Monitoring of the mapper and its connectors
  - **Message Explorer** - inspect live inbound/outbound broker messages and create mappings from them
  - **Snoop Explorer** - review snooped payloads captured during a snoop session
  - **Mapping Tree** - hierarchical view of all active mappings
  - **Test Device** - simulate a device to test mappings end-to-end
  - **AI-assisted mapping** - generate mapping substitutions from a natural-language prompt
  - **Import/Export** - bulk import and export of mapping definitions

### Payload Formats (MappingType)

| Format | Description |
|--------|-------------|
| `JSON` | Standard JSON payload (default) |
| `FLAT_FILE` | Delimited flat-file payloads |
| `HEX` | Raw hexadecimal byte payloads |
| `PROTOBUF_INTERNAL` | Protobuf-encoded payloads |
| `SPARKPLUGB` | SparkPlug B payloads over MQTT |
| `ANY_PAYLOAD` | Generic binary/text, processed entirely by an extension or Smart Function |
| `CODE_BASED` | Payload handled by a code-based (JavaScript) transformation |

### Transformation Types (TransformationType)

| Type | Description |
|------|-------------|
| `DEFAULT` | Simple field substitution via the graphical mapper |
| `JSONATA` | Expression-based transformation using [JSONata](https://jsonata.org) |
| `SMART_FUNCTION` | User-supplied JavaScript executed in a GraalVM polyglot sandbox |
| `EXTENSION_JAVA` | Custom Java processor extension uploaded as a plugin JAR |

> **Please Note:** When using MQTT or any other Message Broker beside MQTT Service you need to provide this broker available yourself to use the Dynamic Mapper.

The mapper processes messages in both directions:

1. `INBOUND`: from Message Broker to C8Y
2. `OUTBOUND`: from C8Y to Message Broker

The Dynamic Mapper can be deployed as a **multi tenant microservice** which means you can deploy it once in your enterprise tenant and subscribe additional tenants using the same hardware resources.
It is also implemented to support **multiple broker connections** at the same time. So you can combine multiple message brokers sharing the same mappings.
This implies that all of them use the same topic structure and payload otherwise the mappings will fail.

Incoming messages are processed by the steps in the following diagram. It shows the involved classes:

<p align="center">
<img src="resources/image/Dynamic_Mapper_Diagram_Dispatcher.png"  style="width: 100%;" />
</p>
<br/>
