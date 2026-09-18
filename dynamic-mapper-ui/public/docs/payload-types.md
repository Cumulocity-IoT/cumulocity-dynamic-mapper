---
title: Payload types
---

### Payload types {#payload-types}

The **payload type** tells the mapper how to read the raw bytes of a message. It is chosen in the Add Mapping
dialog and **cannot be changed afterwards**.

Only **JSON** is offered by default. Enable **Expert Mode** in the dialog to see the rest.

The following describes each payload type and how it is processed by the mapper:

#### JSON (default)

The message body is parsed as a JSON object. Recommended for most IoT integrations. All substitution-based
transformation types (JSONata, JavaScript) and Smart Functions operate on the parsed JSON object.

#### Flat File (CSV)

The message body is a delimited text file (comma, semicolon, tab, or custom character). The mapper converts each
line into a JSON array of field values before applying substitutions. Configure the delimiter in the mapping's
general settings. Field values are then accessible as `payload[0]`, `payload[1]`, etc. Example: a CSV line
`sensor01,23.5,°C` becomes `["sensor01", "23.5", "°C"]`.

#### Hexadecimal

The message body is a hex-encoded string (e.g. `68656C6C6F`). The mapper passes the raw hex string to your
transformation. In a Smart Function receive it via `msg.getPayload()` and decode it using the built-in `atob()` /
`btoa()` helpers (see [Binary helpers in Smart Functions](/c8y-pkg-dynamic-mapper/introduction/smartfunction#binary-helpers)).

#### Protobuf

Protocol Buffer binary messages. The mapper deserializes the Protobuf payload into a JSON representation using the
schema registered in the mapping. Because Protobuf requires a compiled schema, this payload type is typically used
together with a **Java Extension** that performs the schema-aware deserialization.

#### Any Payload

Use this when the payload format is unknown, binary (CBOR, XML, custom binary), or when you want full programmatic
control. The raw payload bytes are passed to your transformation as a Base64-encoded string. You must use a
**Smart Function** or **Java Extension** that decodes and interprets the bytes. In a Smart Function, access the
raw data via `msg.getPayload()` and decode it with `atob()`.

#### SparkPlug B

Native first-class support for the [Eclipse Sparkplug B](https://sparkplug.eclipse.org/) protocol over MQTT. For
**inbound** mappings the binary protobuf payload is automatically decoded by the mapper using the
[Eclipse Tahu](https://github.com/eclipse-tahu/tahu) library — no manual Base64 decoding required. For **outbound**
(NCMD / DCMD) mappings, the metric object returned by your Smart Function is automatically serialized to protobuf
binary before publishing. See the [SparkPlug B](/c8y-pkg-dynamic-mapper/introduction/sparkplugb) section for the full protocol details, message types,
and Smart Function API.

Which payload types each connector supports is listed in the connector table in
[Managing connectors](/c8y-pkg-dynamic-mapper/introduction/managing-connectors).
