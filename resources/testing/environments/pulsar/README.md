# Pulsar Test Environment

Local Pulsar standalone (Docker Compose) for testing the **Apache Pulsar** connector
(`PULSAR`) of a locally running dynamic-mapper-service. Same workflow as
[../kafka/README.md](../kafka/README.md): the connector is created **disabled**, the service is
started locally, then the connector is enabled.

```bash
docker compose up -d      # broker on pulsar://localhost:6650, admin on http://localhost:8080
```

The init container creates tenant `t2050305588` (override with `TENANT=...`) and namespace
`mqtt`. `PULSAR_VERSION=...` overrides the image tag (default 4.0.6).

## Files

| File | Purpose |
|------|---------|
| [connector/pulsar-connector.json](connector/pulsar-connector.json) | Connector configuration (disabled) for `POST /configuration/connector/instance` |
| [mappings/sample-mappings.json](mappings/sample-mappings.json) | Two Smart Function mappings (inbound + outbound), import via the UI |

## 1. Create the connector (disabled)

UI: **Connectors → Import**, select `connector/pulsar-connector.json` (a JSON array). Or REST,
sending the single array element:

```bash
jq '.[0]' connector/pulsar-connector.json | c8y api --method POST \
  --url /service/dynamic-mapper-service/configuration/connector/instance \
  --header 'Content-Type: application/json' --data -
```

Start the service (`mvn spring-boot:run -Dspring-boot.run.profiles=dev`, see the Kafka README),
then enable it by PUTting the same body with `"enabled": true` to
`/service/dynamic-mapper-service/configuration/connector/instance/test-pulsar-connector`.

## 2. Import the mappings

UI: **Mapping → Inbound → Import** (and the same under **Outbound**), select
`mappings/sample-mappings.json`. If one direction's import rejects the other direction's entry,
split the file. Then assign each mapping to `test-pulsar-connector` and **Activate** it.

Topics: `pulsar/test/inbound` becomes `persistent://t2050305588/mqtt/pulsar-test-inbound`
(`/` → `-`, tenant/namespace from the connector properties).

The functions end with `export { onMessage };` (required by the service's ESM mode; without it the
mapper logs "Function 'onMessage' not found ... exports nothing"). If your tenant runs with
`supportESM=false`, remove that line.

The connector passes the Pulsar **message key** through: inbound the Smart Function reads it from
`msg.transportFields.key` (falling back to `deviceId` in the payload), outbound the function sets
`transportFields: { key: externalId }`. Pulsar message properties are not supported.

## 3. Inbound test (Pulsar → measurement)

```bash
docker exec pulsar-standalone bin/pulsar-client produce \
  persistent://t2050305588/mqtt/pulsar-test-inbound \
  -k pulsar-dev-001 -m '{"temperature":21.5}'
```

Expect a device with external ID `pulsar-dev-001` (type `c8y_Serial`) and a
`c8y_TemperatureMeasurement`. The topic is auto-created on first publish; subscribe happens at
connect, so if the mapping was activated before the topic existed, run the produce once and
re-activate/reconnect if nothing arrives.

## 4. Outbound test (measurement → Pulsar)

The outbound mapping needs a device with an external ID and a notification subscription on
measurements (Smart Function reads the external ID from the device):

1. Create a device, add external ID `pulsar-dev-001` (type `c8y_Serial`).
2. Subscribe it for `MEASUREMENT` (device subscription in the mapper UI).
3. Watch the output topic, then create a measurement for the device:

```bash
docker exec pulsar-standalone bin/pulsar-client consume \
  persistent://t2050305588/mqtt/pulsar-test-outbound-pulsar-dev-001 -s test-sub -n 0

c8y measurements create --device <deviceId> --type c8y_TemperatureMeasurement \
  --data 'c8y_TemperatureMeasurement.T.value=23.4,c8y_TemperatureMeasurement.T.unit=C'
```
