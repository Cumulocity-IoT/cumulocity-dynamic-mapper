# HiveMQ Test Environment

Local MQTT broker (HiveMQ Community Edition, Docker Compose) for testing the **MQTT** connector
(`MQTT`) of a locally running dynamic-mapper-service. Same workflow as
[../pulsar/README.md](../pulsar/README.md) and [../kafka/README.md](../kafka/README.md): the
connector is created **disabled**, the service is started locally, then the connector is enabled.

The broker has no authentication and is for local testing only.

```bash
docker compose up -d      # plain MQTT on localhost:1883, MQTT over TLS on localhost:8883
docker compose ps         # wait for "healthy"
```

| Port | Use |
|------|-----|
| `1883` | Plain MQTT (override with `HIVEMQ_MQTT_PORT`) |
| `8883` | MQTT over TLS, server certificate `CN=localhost` from `hivemq-conf/keystore.jks` (override with `HIVEMQ_MQTTS_PORT`) |

`HIVEMQ_VERSION=...` overrides the image tag (default `latest`). The control center is not published,
because its port 8080 is the one the local dynamic-mapper-service uses.

> **Port 1883 already in use?** Check with `lsof -nP -iTCP:1883 -sTCP:LISTEN`. An SSH tunnel, a local
> Mosquitto, or the [Sparkplug environment](../sparkplug/README.md) (which also publishes 1883) will
> take it, and `localhost:1883` then reaches the wrong process. Start HiveMQ on another port,
> `HIVEMQ_MQTT_PORT=1884 docker compose up -d`, and use that port in the connector's `mqttPort`.

## Local setup

The cloud tenant cannot reach `localhost:1883`, so the dynamic-mapper-service must run locally and the
local UI must send its REST calls to it. Follow the "Local setup" section of the
[Pulsar README](../pulsar/README.md#local-setup-pulsar-backend-and-ui-all-on-your-machine) (backend with
`APP_ADDITIONALSUBSCRIPTIONIDTEST`, UI with `proxy.local-backend.conf.cjs`). Only the broker differs.

## Files

| File | Purpose |
|------|---------|
| [docker-compose.yaml](docker-compose.yaml) | HiveMQ CE with the configuration below |
| [hivemq-conf/config.xml](hivemq-conf/config.xml) | Listeners: plain TCP 1883 and TLS 8883, no auth |
| [hivemq-conf/keystore.jks](hivemq-conf/keystore.jks) | Self-signed server certificate for the TLS listener (password `changeme`) |
| [connector/mqtt-connector.json](connector/mqtt-connector.json) | Connector configuration (disabled), `POST /configuration/connector/instance` |
| [mappings/sample-mappings.json](mappings/sample-mappings.json) | Two Smart Function mappings (inbound + outbound), import via the UI |

## 1. Create the connector (disabled)

UI: **Connectors → Import**, select `connector/mqtt-connector.json` (a flat JSON array). The import gives
the connector a new random identifier and keeps it disabled. Or REST, which keeps the identifier
`test-mqtt-connector`:

```bash
jq '.[0]' connector/mqtt-connector.json | c8y api --method POST \
  --url /service/dynamic-mapper-service/configuration/connector/instance \
  --header 'Content-Type: application/json' --data -
```

Start the service, then enable the connector in the UI, or PUT the same body with `"enabled": true` to
`/service/dynamic-mapper-service/configuration/connector/instance/test-mqtt-connector`. The connector
settings are MQTT 3.1.1 over `mqtt://localhost:1883`, a persistent session (`cleanSession=false`) and no credentials. The persistent session matters for reliability: with a clean session the broker forgets unacknowledged messages when the connector reconnects, which is how a failed inbound message is redelivered. For the TLS
listener use protocol `mqtts://`, port `8883`, and trust the certificate (see the connector's
"trust custom certificate" options).

## 2. Import the mappings

UI: **Mapping → Inbound → Import** (and the same under **Outbound**), select `mappings/sample-mappings.json`.
If one direction's import rejects the other direction's entry, split the file. Assign each mapping to
the MQTT connector and **Activate** it.

The functions end with `export { onMessage };` (required by the service's ESM mode). Remove that line
only if your tenant runs with `supportESM=false`. The outbound function sets the topic itself
(`mqtt/test/outbound/<externalId>`), which is why the mapping has no `publishTopic`.

## 3. Inbound test (MQTT → measurement)

```bash
mosquitto_pub -h localhost -p 1883 -t mqtt/test/inbound -q 1 \
  -m '{"deviceId":"mqtt-dev-001","temperature":21.5}'
```

Expect a device with external ID `mqtt-dev-001` (type `c8y_Serial`) and a `c8y_TemperatureMeasurement`.

## 4. Outbound test (measurement → MQTT)

1. Create a device and add external ID `mqtt-dev-001` (type `c8y_Serial`).
2. Subscribe it for `MEASUREMENT` (device subscription in the mapper UI).
3. Watch the topic, then create a measurement:

```bash
mosquitto_sub -h localhost -p 1883 -t 'mqtt/test/outbound/#' -v

c8y measurements create --device <deviceId> --type c8y_TemperatureMeasurement \
  --data 'c8y_TemperatureMeasurement.T.value=23.4,c8y_TemperatureMeasurement.T.unit=C'
```

## Reliability test

To check that outbound messages survive a broker outage, see
[../../reliability/broker-outage/README.md](../../reliability/broker-outage/README.md).

## Troubleshooting

| Symptom | Cause and fix |
|---------|---------------|
| The connector cannot connect although HiveMQ is healthy | `localhost:1883` reaches another process (see the port note above). Use `HIVEMQ_MQTT_PORT` and the same port in the connector. |
| `docker compose up` fails with "port is already allocated" | Same cause. Free the port or set `HIVEMQ_MQTT_PORT` and `HIVEMQ_MQTTS_PORT`. |
| TLS connection is rejected | The certificate is self-signed for `CN=localhost`. Enable "trust custom certificate" on the connector, or use plain `mqtt://`. |
