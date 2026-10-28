# Pulsar Test Environment

Local Pulsar standalone (Docker Compose) for testing the **Apache Pulsar** connector
(`PULSAR`) of a locally running dynamic-mapper-service. Same workflow as
[../kafka/README.md](../kafka/README.md): the connector is created **disabled**, the service is
started locally, then the connector is enabled.

```bash
docker compose up -d      # broker on pulsar://localhost:6650, admin on http://localhost:9090 (override with PULSAR_ADMIN_PORT)
```

The init container creates tenant `example-tenant` (override with `TENANT=...`) and namespace
`mqtt`. `PULSAR_VERSION=...` overrides the image tag (default 4.0.6).

## Local setup (Pulsar, backend, and UI all on your machine)

The cloud tenant cannot reach `localhost:6650`, so the dynamic-mapper-service must run locally
and the local UI must send its REST calls to it.

1. **Pulsar:** `docker compose up -d` (this directory). Ports: broker `6650`, admin `9090`.
2. **Backend** (port `8080`; stop any older instance first, see Troubleshooting). Use the same tenant
   as the UI, as in the [Kafka README](../kafka/README.md#3-start-the-dynamic-mapper-service-locally):

   ```bash
   cd dynamic-mapper-service
   export C8Y_BASEURL="https://<your-tenant>.cumulocity.com"
   export C8Y_BOOTSTRAP_TENANT="<tenant-id>"
   export C8Y_BOOTSTRAP_USER="servicebootstrap_dynamic-mapper-service"
   export C8Y_BOOTSTRAP_PASSWORD="<bootstrap-password>"
   export C8Y_MICROSERVICE_ISOLATION="MULTI_TENANT"
   mvn spring-boot:run -Dspring-boot.run.profiles=dev
   ```

3. **UI** with a proxy that sends `/service/dynamic-mapper-service/**` to the local backend and
   everything else to the tenant ([proxy.local-backend.conf.cjs](../../../../dynamic-mapper-ui/proxy.local-backend.conf.cjs);
   set `DM_LOCAL_BACKEND` if the backend is not on `http://localhost:8080`):

   ```bash
   cd dynamic-mapper-ui
   npm run start -- --url https://<your-tenant>.cumulocity.com --proxy-config proxy.local-backend.conf.cjs
   ```

4. Import and enable the connector (section 1), then follow section 3. To watch raw messages, open
   the **Message Explorer** in the local UI, pick the Pulsar connector, start a session (for
   example topic `#`), and **then** produce a message. A new subscription starts at "latest", so
   messages produced before the session started are not shown.

## Files

| File | Purpose |
|------|---------|
| [connector/pulsar-connector.json](connector/pulsar-connector.json) | Connector configuration (disabled) for `POST /configuration/connector/instance` |
| [mappings/sample-mappings.json](mappings/sample-mappings.json) | Two Smart Function mappings (inbound + outbound), import via the UI |

## 1. Create the connector (disabled)

UI: **Connectors → Import**, select `connector/pulsar-connector.json` (a flat JSON array of
connectors). The import gives the connector a new random identifier and keeps it disabled; read the
identifier from the Connectors page and use it wherever `test-pulsar-connector` appears below.
Or REST, sending the single array element (this keeps the identifier `test-pulsar-connector`):

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

Topics: `pulsar/test/inbound` becomes `persistent://example-tenant/mqtt/pulsar-test-inbound`
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
  persistent://example-tenant/mqtt/pulsar-test-inbound \
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
  persistent://example-tenant/mqtt/pulsar-test-outbound-pulsar-dev-001 -s test-sub -n 0

c8y measurements create --device <deviceId> --type c8y_TemperatureMeasurement \
  --data 'c8y_TemperatureMeasurement.T.value=23.4,c8y_TemperatureMeasurement.T.unit=C'
```

## Troubleshooting

| Symptom | Cause and fix |
|---------|---------------|
| `pulsar-standalone` exits with code 1, log shows `Bookie handle is not available` | The `pulsardata` volume holds a bookie registered under an old container IP. Run `docker compose down -v`, then `docker compose up -d`. This deletes the local Pulsar data. |
| Backend fails with `Port 8080 was already in use` | An earlier backend instance (or other process) still listens on 8080. Find it with `lsof -nP -iTCP:8080 -sTCP:LISTEN` and stop it. Pulsar's admin port is `9090` and does not use 8080 on your machine. |
| Message Explorer stays empty | Start the explorer session before producing the message. Check in the browser network tab that `/service/dynamic-mapper-service/explorer/...` returns 200 from the local backend. |
| Connector import shows an error | The error text now includes the HTTP status and server message. The file must be a flat array (`[ {...} ]`), not an array inside an array. |
