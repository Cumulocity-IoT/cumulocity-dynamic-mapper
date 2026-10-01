# Cumulocity Dynamic Mapper

Dynamic Mapper lets devices and other systems exchange arbitrary payloads with Cumulocity over
MQTT, Kafka, HTTP, AMQP, Apache Pulsar, or Google Cloud Pub/Sub, providing the following artifacts:

* A **Microservice** - hosts connectors for all of the brokers/protocols above, listens for
  incoming messages, and applies the configured mappings in either direction. Exposes REST
  endpoints for the UI to manage connector configurations and mappings.
* A **Frontend Plugin** - uses those endpoints to configure broker connections and to perform
  mapping within the Cumulocity IoT UI, either graphically or in code. Mappings can be defined as
  JavaScript **Smart Functions** — full programmatic transformations with access to device
  inventory data, multiple outputs, and persistent state across messages — as declarative
  [JSONata](https://jsonata.org/) expressions for simple field mappings, or as a Java processor
  extension; an AI agent can also generate a first draft of a mapping from a sample payload.

Using the solution you are able to connect to any of the supported brokers and map arbitrary
payloads on any topic dynamically to the Cumulocity IoT Domain Model, without writing or
deploying custom device agents or firmware. Connectivity covers MQTT, Kafka, HTTP, AMQP 0-9-1/1.0,
Apache Pulsar, Google Cloud Pub/Sub, Webhooks, and the internal Cumulocity API, in both inbound and
outbound direction depending on the connector — see the in-app **Connector Reference** page (or the
[app documentation source](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper/blob/main/dynamic-mapper-ui/public/docs/connectors.md))
for the full connector/direction/payload-format matrix.

The mapper processes messages in both directions:
1. `INBOUND`: from external source to C8Y
2. `OUTBOUND`: from C8Y to external source

Mappings are defined in a graphical stepper wizard: pick a connector, a payload format (JSON, Flat
File, Hexadecimal, Protobuf, Any Payload, or native SparkPlug B), and a transformation type.

**Smart Functions** are the most flexible and most-used transformation type: write the
`onMessage(msg, context)` function in JavaScript and return the fully-built Cumulocity object(s)
directly, with access to device inventory data, multiple outputs from a single message, binary/CBOR
payload decoding, and per-mapping persistent state (counters, running averages, deduplication)
across invocations — no substitution rules to configure. Ready-to-use code templates are provided
per direction, and are fully customizable. For simple field-to-field mappings without any
JavaScript, declarative **JSONata** expressions cover the same ground with less setup. A **Java
Extension** is also available for enterprise-grade, compiled, type-safe transformations.
<br>
<br>
![Add mapping](image/Dynamic_Mapper_Mapping_Table_Add_Modal.png)
<br>
<br>
![Define mappings](image/Dynamic_Mapper_Mapping_Stepper_SmartFunction.png)
<br>
<br>
The Dynamic Mapper is **AI-empowered**: assign an AI agent (via the Cumulocity AI Agent Manager) to
generate Smart Function JavaScript or JSONata expressions from a sample payload and a
natural-language description, instead of writing the transformation by hand.

Once connected, use the **Message Explorer** to capture live broker or Notification 2.0 traffic and
build a mapping directly from a captured payload. Before activating a mapping, **test** it against
sample or live payloads, then track it at runtime via **Monitoring** — per-mapping statistics and
charts, cache statistics, service events, and the topic-matching hierarchy — down to per-mapping
debug logging when something needs closer inspection.

For the complete documentation, including the full connector matrix, QoS/reliability behavior per
connector, and role-based access control, please check the GitHub project
[cumulocity-dynamic-mapper](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper).

**NOTE:** 
* This solution requires an additional microservice. The microservice `dynamic-mapper-service.zip` can be found in the [release section](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper/releases) of the github project. Instructions on how to deploy the microservice can be found in the [Installation Guide](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper/blob/main/docs/installation.md).
* The solution was renamed from **Dynamic-mapping**, **MQTT-mapping** to **Cumulocity Dynamic Mapper**. If you still want to use previous releases ( **Mqtt-mapping** < 4.0.0, **Dynamic-mapping** < 5.5.0), search the [release section](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper/releases?q=Mqtt-mapping&expanded=true) for tags below `5.5.0` — a fixed page number breaks as new releases are published.