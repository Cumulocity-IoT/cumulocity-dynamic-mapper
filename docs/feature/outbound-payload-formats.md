# Outbound payload formats

What an outbound Smart Function or Java extension can put on the wire. Inbound, a mapping of type `ANY_PAYLOAD` accepts any
broker payload (text, XML, Protobuf). Outbound the input is always a Cumulocity JSON object, so "any
payload" means the **output**: a device command as a raw string, a Protobuf message, a firmware chunk.

## Requirements

**What it is for.** Sending a device the exact bytes its protocol expects, for example an OTA command that
is one ASCII string such as `AT+GTUPD=…$`, without the mapper wrapping it in JSON.

1. **The function or extension chooses the format.** Each message a Smart Function or Java extension returns
   can be a JSON object, text, or binary. Two messages returned by one call can differ. Both use the same
   fields and the same rules.
2. **JSON stays the default.** A function that returns an object behaves as before: the mapper serializes it,
   fills in the device identity and extracts topic levels. No existing mapping changes.
3. **Text is sent as given.** A string payload reaches the broker character for character: no quotes, no JSON
   escaping, no added line break.
4. **Binary is sent as bytes.** A string that holds Base64 or hexadecimal text is decoded, and the bytes are
   sent. Whitespace in the string is ignored. Text that is not valid Base64 or hex fails that message with an
   error that names the encoding, and nothing is sent for it.
5. **The format can be stated, and is inferred when it is not.** An object is JSON. A string is text, with one
   exception: a string that is valid JSON (`{"a":1}`, `[1,2]`, `123`, `true`) is sent unchanged with the
   connector's default content type, not `text/plain`, so it reaches the receiver as it did before.

   A Java extension may also return bytes, which are sent as bytes. Bytes that are valid JSON are treated as the
   equivalent string.

   One difference between the two transformations is kept for compatibility: a Java extension's string or bytes
   that start with `{` are a JSON object the extension serialized itself, and still take the regular path (device
   identity enrichment, possible routing to a Cumulocity API). A Smart Function never had that path, so its
   `{`-string is simply sent unchanged. Either can state the format to send such text as it is.
6. **A stated format must match the payload.** Text, Base64 and hex require a string; anything else, and any
   unknown format name, fails the message.
7. **A content type can accompany the message.** The function may set one. Without it, text is sent as
   `text/plain` and binary as `application/octet-stream`; JSON, including a string or bytes that are valid
   JSON, keeps the connector's own default. A connector that has no place for a content type ignores
   it.
8. **Text and binary go to the broker only.** They are never routed to a Cumulocity API, so
   `cumulocityType` and a topic that starts with an API name have no effect on them, and topic-level tokens
   inside the payload are not processed.
9. **The destination is chosen by the function.** The topic comes from the returned message or the mapping's
   publish topic, as for JSON. `_externalId_` in the topic is still resolved.
10. **Offered in the editor.** In the mapping type list, **Any Payload** is available for outbound mappings
    with the Smart Function or the Java Extension transformation. The Cumulocity source object is edited as
    for any other outbound mapping; the target is whatever the function or extension returns.
11. **Testable.** Dry Run shows what the mapping would send. A binary result is shown as its Base64 or hex
    text, not decoded.

Per connector, what is carried:

| Connector | Text | Binary | Content type |
|---|:---:|:---:|---|
| MQTT 3 | yes | yes | not carried |
| MQTT 5 | yes | yes | MQTT 5 content type property |
| MQTT Service (Pulsar) | yes | yes | not carried |
| Pulsar (native) | yes | yes | not carried |
| Google Pub/Sub | yes | yes | not carried |
| Kafka | yes | yes | `content-type` record header |
| AMQP 0.9.1 | yes | yes | message content type |
| AMQP 1.0 | yes (text message) | yes (bytes message) | message content type, replaces the connector's `contentType` property |
| Webhook (external system) | yes | yes | `Content-Type` header |
| Webhook (Cumulocity internal) | no | no | always JSON |

Deliberate non-goals:

- **No decoding of binary on the mapping side.** The mapper never interprets the bytes; protocol framing,
  checksums and escaping are the function's job.
- **No conversion for a connector that cannot carry it.** The HTTP and HTTP polling connectors do not
  publish outbound, so the setting has no effect there.
- **No content type on Pulsar, MQTT 3 and Pub/Sub.** Their message models are not extended for it.
- **No automatic acknowledgement from the device.** A command is delivered like any outbound message;
  reading the device's reply is an inbound mapping.

## Implementation

| Concern | Where |
|---|---|
| Deciding the format, shared by both transformations | [`OutboundRawPayload`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/util/OutboundRawPayload.java) (`resolve`) |
| Output fields and their documentation (the contract) | `payloadEncoding` and `contentType` on `DeviceMessage` in `dynamic-mapper-smart-function/src/types/smart-function-dynamic-mapper.types.ts`; mirrored in [`DeviceMessage.java`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/processor/model/DeviceMessage.java) |
| Reading the fields from the JavaScript result | `JavaScriptInteropHelper.convertToDeviceMessage` |
| Applying it, Smart Function | `FlowResultOutboundProcessor.processDeviceMessage` |
| Applying it, Java extension | `ExtensibleResultOutboundProcessor.processDeviceMessage`; the extension returns `DeviceMessage[]`, built with `DeviceMessage.forTopic(…).payload(…).payloadEncoding(…).contentType(…)` |
| Carrying bytes and content type to the connector | `DynamicMapperRequest.binaryPayload` and `contentType` |
| Connectors | `KafkaClientV2`, `AMQPClient`, `AMQP10Client`, `WebHook`, `MQTT5Client`, `PulsarConnectorClient` under `dynamic-mapper-service/src/main/java/dynamic/mapper/connector/`; `MQTT3Client`, `MQTTServicePulsarClient` and `GooglePubSubClient` already read `binaryPayload` (added for SparkPlug B) |
| Editor | `MappingTypeDescriptionMap` in `shared/mapping/mapping-type-config.ts`, the `ANY_PAYLOAD` rule in `shared/mapping/stepper-configuration.strategy.ts`, `requiresRawPayload()` in `mapping/step-testing/mapping-step-testing.component.ts` |
| Example | `template-SMART-OUTBOUND-07.js` in `dynamic-mapper-service/src/main/resources/templates/` |

### How a message is routed

Both result processors (`FlowResultOutboundProcessor` for Smart Functions, `ExtensibleResultOutboundProcessor`
for Java extensions) ask `OutboundRawPayload.resolve` first, so the rules cannot differ between them. It returns `null` for a JSON object, which then takes
the long-standing path (the Smart Function path clones, enriches, serializes and derives the Cumulocity API; the
extension path serializes and derives the API). Otherwise it returns a `Raw(text, bytes, contentType)` and the
processor:

- skips the clone and `setResolvedPublishTopic` (Smart Function only), because there is no object to read topic
  levels from;
- uses `text` as the request body and, for binary, also sets `bytes` on the request;
- skips `OutboundRequestFinalizer.deriveApi` and `finalizeRequest`, so a text payload cannot be turned into a
  Cumulocity request by a topic that happens to start with `event/`;
- still resolves the `_externalId_` token in the topic and applies transport fields and the retain flag.

| `payloadEncoding` | Payload | Result |
|---|---|---|
| none | object | JSON, regular path |
| none | string starting with `{`, Java extension | regular path (legacy: serialized JSON) |
| none | string that is a JSON object, Smart Function | unchanged, connector default content type |
| none | `byte[]` that is a JSON array or scalar | JSON text, connector default content type |
| none | any other `byte[]` (Java extension) | bytes, `application/octet-stream` |
| `json` | `byte[]` | regular path (UTF-8 JSON text) |
| none | `byte[]` starting with `{`, Java extension | regular path (legacy: UTF-8 JSON text) |
| none | string that is a JSON array or scalar | unchanged, connector default content type |
| none | any other string | text, `text/plain` |
| `json` | object | JSON, regular path |
| `json` | string | a JSON string value, with quotes |
| `text` | string | text, `text/plain` |
| `base64`, `hex` | string | bytes, `application/octet-stream` |
| `text`, `base64`, `hex` | not a string (including `byte[]`) | error |
| anything else | any | error |

### Choices and traps

- **Why the `{` rule differs.** A Java extension could always return a JSON string, and it was sent as JSON text
  with identity enrichment and API routing. Sending it as plain text would silently drop that, so the extension
  keeps the regular path (`OutboundRawPayload.resolve(…, true)`). A Smart Function never could:
  `clonePayload` converts a `String` with Jackson's `convertValue`, which cannot turn a string into a map, so such
  a payload failed with "Failed to clone payload". There is nothing to stay compatible with, so it is sent
  unchanged (`resolve(…, false)`). The mapper cannot fill in the device identity or topic levels of a string,
  which is why returning the object stays the recommendation.
- **Why a JSON array or scalar keeps the connector content type.** An extension's string `[1,2]` was sent with
  the connector's JSON content type (webhook `application/json`, AMQP `application/json`). Defaulting it to
  `text/plain` would change the header a receiver sees for the same bytes. `OutboundRawPayload` therefore checks,
  with a strict parse, whether a string or byte array that starts like JSON is a whole JSON document. A first
  character that cannot start JSON (an AT command starts with `A`) is rejected without a parse, and a byte array
  is looked at byte by byte, never copied into a string first.
- **Why an extension may return `byte[]`.** Java has a real byte type, so an extension need not encode bytes as
  text. A JavaScript function cannot do this reliably, which is why Smart Functions use `base64` or `hex`.
  Before this feature a `byte[]` was decoded as UTF-8 text, which corrupted binary; JSON bytes still take
  that path. The shipped `ProcessorExtensionAlarmToSparkplugB` returns protobuf bytes and benefits.
- **Why Base64, not `Uint8Array`.** The runtime turns a typed array into a list of numbers, which it cannot
  tell from a serialized JSON document (an existing path reads such a list as UTF-8 JSON). Base64 also
  matches inbound Any Payload, where the function receives Base64.
- **The request keeps the encoded text.** For binary, `DynamicMapperRequest.request` holds the Base64 or hex
  string and `binaryPayload` the bytes. The text is what Dry Run shows and what logs print; connectors send the
  bytes. A connector that read only `request` would send the encoded text, which is why every connector that
  publishes outbound reads `binaryPayload` first.
- **Kafka values are `byte[]`.** The producer's value serializer changed from `StringSerializer` to
  `ByteArraySerializer`. Text is encoded as UTF-8, which is what the string serializer produced, so existing
  Kafka mappings are unchanged. Serializers are not configurable: the connector re-applies them after the
  connector's "Additional producer properties" and logs a warning if one was overridden, because a custom
  `value.serializer` would fail every send.
- **Earlier all-args constructors are kept.** `DeviceMessage` and `DynamicMapperRequest` use Lombok's
  all-args constructor, whose signature changes when a field is added. An extension JAR compiled earlier would
  fail with a `NoSuchMethodError`, so the previous signatures are declared explicitly
  (`DynamicMapperRequestCompatibilityTest`).
- **Behavior change for SparkPlug B off MQTT.** A request with `binaryPayload` (SparkPlug B) is now sent as
  bytes by every connector that reads it. Kafka, AMQP, AMQP 1.0, native Pulsar and an external webhook used to
  ignore it and send the JSON text.
- **The Webhook raw body applies to external systems only.** The Cumulocity-internal path rewrites the body
  (identity, GET-merge-PUT for patch) and always sends JSON, so the connector passes no raw request there.
- **The HTTP content type is no longer fixed.** POST, PUT and external PATCH use the request's content type, or
  JSON when it has none.
- **The editor's test input.** `requiresRawPayload()` treats HEX, flat file and **inbound** Any Payload as
  "send the `payload` string". Outbound Any Payload must send the whole Cumulocity JSON object, so the check
  is direction-aware; without it a Dry Run would have sent only a `payload` property.
- **Changing the contract.** Follow [changing-the-runtime-api.md](../changing-the-runtime-api.md): the Java
  class first, then the TypeScript type, `npm run generate:editor-api`, then `smartfunction.md` and the AI prompt.

### Testing

| What | Where |
|---|---|
| The whole outbound chain, Smart Function (real GraalVM JavaScript) and Java extension, ending in a connector that records what it publishes | `OutboundAnyPayloadPipelineIntegrationTest` |
| The rules themselves | `OutboundRawPayloadTest` |
| Constructors of earlier versions | `DynamicMapperRequestCompatibilityTest` |
| Smart Function: format selection, decoding, errors, content type defaults | `FlowResultOutboundProcessorTest` |
| Java extension: text, bytes, encoding, content type, errors | `ExtensibleResultOutboundProcessorTest` |
| Kafka bytes, `content-type` header, forced serializers | `KafkaClientV2BrokerOutageTest` |
| AMQP 0.9.1 bytes and content type | `AMQPClientTest` |
| AMQP 1.0 bytes message, text message, content type | `AMQP10ClientPublishTest` |
| MQTT 5 content type | `MQTT5ClientPublishTest` |
| Webhook body and `Content-Type` on the wire | `WebHookPublishTest` |
| Native Pulsar bytes | `PulsarConnectorClientTest` |
| Type contract | `smart-function-dynamic-mapper.types.spec.ts` |
| Outbound Any Payload configuration | `stepper-configuration.strategy.spec.ts` |

The connector tests check what is handed to the transport, not a real broker: the Kafka, Pulsar and AMQP
0.9.1 clients against mocks, AMQP 1.0 against a mocked JMS session, MQTT 5 against a mocked client (the publish
message is inspected), and the Webhook over a real local HTTP server, so the body bytes and the `Content-Type`
header on the wire are observed. None of them ran against a real RabbitMQ, Artemis, Mosquitto or Kafka.
