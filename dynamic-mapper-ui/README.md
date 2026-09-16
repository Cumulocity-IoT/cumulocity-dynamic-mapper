# Cumulocity Dynamic Mapper

Dynamic Mapper lets devices and other systems exchange arbitrary payloads with Cumulocity over
MQTT, Kafka, HTTP, AMQP, Apache Pulsar, or Google Cloud Pub/Sub, providing the following artifacts:

* A **Microservice** - hosts connectors for all of the brokers/protocols above, listens for
  incoming messages, and applies the configured mappings in either direction. Exposes REST
  endpoints for the UI to manage connector configurations and mappings.
* A **Frontend Plugin** - uses those endpoints to configure broker connections and to perform
  mapping within the Cumulocity IoT UI, either graphically or in code. Mappings can be defined
  using [JSONata](https://jsonata.org/) expressions, as JavaScript ("Smart Functions"), or as a
  Java processor extension; an AI agent can also generate a first draft of a mapping from a
  sample payload.

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
File, Hexadecimal, Protobuf, Any Payload, or native SparkPlug B), and a transformation type —
declarative **JSONata** expressions for straightforward field mappings, a JavaScript **Smart
Function** for full programmatic control (device inventory access, multiple outputs, stateful
processing), or a **Java Extension** for enterprise-grade, compiled transformations.
<br>
<br>
![Add mapping](image/Dynamic_Mapper_Mapping_Table_Add_Modal.png)
<br>
<br>
![Define mappings](image/Dynamic_Mapper_Mapping_Stepper_Substitution_Basic.png)
<br>
<br>
The Dynamic Mapper is **AI-empowered**: assign an AI agent (via the Cumulocity AI Agent Manager) to
generate JSONata expressions or Smart Function code from a sample payload and a natural-language
description, instead of writing the transformation by hand.

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
* The solution was renamed from **Mqtt-mapping** to **Cumulocity Dynamic Mapper**. If you still want to use previous releases (**Mqtt-mapping** < 4.0.0), search the [release section](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper/releases?q=Mqtt-mapping&expanded=true) for tags below `4.0.0` — a fixed page number breaks as new releases are published.