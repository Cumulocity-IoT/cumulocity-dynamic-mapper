### Overview of Dynamic Mapper {#overview}

The Cumulocity Dynamic Mapper lets you connect to almost any message broker and map any payload to the
Cumulocity format. Define mappings using an intuitive graphical editor. During operation, your custom payloads are
automatically converted to match the Cumulocity IoT Domain Model, ensuring seamless integration and data flow.

This page contains a complete introduction of the Dynamic Mapper, however the following links help you get
additional information on the Dynamic Mapper:

- Find detailed documentation for the [Dynamic Mapper](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper) in the Git repository.
- Mappings use [JSONata](https://jsonata.org/), a lightweight query and transformation language for JSON data.

:::info What is Dynamic Mapper?
It acts as a bridge between your devices/systems and Cumulocity IoT, translating custom data formats into
Cumulocity's standardized domain model. This eliminates the need for custom device agents or firmware
modifications.
:::

The main resources in the Mapper are: **connectors** and **mappings**.
To receive messages from an MQTT broker (Cumulocity MQTT Service, Hive MQ, Mosquitto, etc.), use an **inbound
mapping**.
Mapping rules are applied to transform the payload for any of the [Cumulocity APIs](https://cumulocity.com/docs/concepts/domain-model/).
For outbound communication, define an **outbound mapping**. At runtime, it listens for changes to core
[domain objects](https://cumulocity.com/docs/concepts/domain-model/), applies the outbound mapping - transforming
the Cumulocity payload - and sends it using the configured connector to a broker.

Use the following links to access resources in the Dynamic Mapper:
