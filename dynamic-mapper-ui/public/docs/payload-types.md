---
title: Payload types
---

### Payload types {#payload-types}

The **payload type** tells the mapper how to read the raw bytes of a message. It is called **Payload Format** in the
**Add mapping** dialog, and it **cannot be changed afterwards**.

The dialog offers **JSON Payload** by default. Flat File, Hexadecimal, SparkPlug B, and Any Payload are in the same
list, and Expert Mode is not needed to select them. Which of them are available depends on the direction:

| Payload type | Inbound | Outbound |
|---|:---:|:---:|
| JSON | Yes | Yes |
| Flat File (CSV) | Yes | No |
| Hexadecimal | Yes | No |
| Any Payload | Yes | No |
| SparkPlug B | Yes | Yes (NCMD and DCMD) |

The following describes each payload type and how the mapper processes it:

#### JSON (default)

The message body is parsed as a JSON object. Recommended for most IoT integrations. Smart Functions and JSONata
expressions operate on the parsed JSON object. If the payload is a JSON array instead of an object, use a Smart
Function.

#### Flat File (CSV)

The message body is a delimited text file. The mapper does not split it. Your transformation receives the whole
text in a `payload` field and splits it itself, for example with the JSONata expression `$split(payload, /,\s*/)`
or with `msg.getPayload()["payload"].split(",")` in a Smart Function. A CSV line `sensor01,23.5,°C` becomes
`["sensor01", "23.5", "°C"]`. Flat File mappings are inbound only.

#### Hexadecimal

The message body is converted to a hex string with a leading `0x` (for example `0x68656c6c6f`) and passed to your
transformation in the `payload` field. Convert it to a number with the JSONata function `$number("0x5a75")`, which
returns `23157`, or with `parseInt(msg.getPayload()["payload"], 16)` in a Smart Function. Hexadecimal mappings are inbound only.

#### Any Payload

Use this when the payload format is unknown or binary (Protobuf, CBOR, XML, custom binary), or when you want full
programmatic control. The raw payload bytes are passed to your transformation as a Base64-encoded string. You must
use a **Smart Function** or **Java Extension** that decodes and interprets the bytes. In a Smart Function, read the
raw data with `msg.getPayload()` and decode it with `atob()`. Any Payload mappings are inbound only.

:::important
The former payload types **Protobuf** and **Payload parsed in Java Extension** are deprecated and no longer offered
when you create a mapping. Existing mappings keep working. For new mappings, use **Any Payload** and decode the
Protobuf message in a Smart Function or Java Extension.
:::

#### SparkPlug B

Native first-class support for the [Eclipse Sparkplug B](https://sparkplug.eclipse.org/) protocol over MQTT. For
**inbound** mappings the binary protobuf payload is automatically decoded by the mapper (using the message schema
published by [Eclipse Tahu](https://github.com/eclipse-tahu/tahu)), so no manual Base64 decoding is required. For **outbound**
(NCMD / DCMD) mappings, the metric object returned by your Smart Function is automatically serialized to protobuf
binary before publishing. Only Smart Function transformations are supported. See [SparkPlug B](/c8y-pkg-dynamic-mapper/introduction/sparkplugb) for the full protocol details, message types,
and Smart Function API.

Which payload types each connector supports is listed in the connector table in
[Managing connectors](/c8y-pkg-dynamic-mapper/introduction/managing-connectors).
