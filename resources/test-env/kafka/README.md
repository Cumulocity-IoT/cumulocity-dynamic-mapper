# Kafka Test Environment

Local Kafka broker (KRaft mode, single node) using Docker Compose, for testing
the Kafka connector of the dynamic-mapper-service without a real Kafka cluster.

## Prerequisites

- Docker Desktop installed and running
- The dynamic-mapper-service connects to a **real Cumulocity tenant** (cloud or
  self-hosted) — only the Kafka broker itself is local. You need valid
  Cumulocity credentials/bootstrap user for that tenant.
- [go-c8y-cli](https://goc8ycli.netlify.app/) (`c8y`) for the REST calls below,
  or plain `curl` (both shown).

## Why "create disabled, then enable via REST"

A connector configuration created through the UI or API is immediately
persisted, and if `enabled: true` the microservice tries to connect right
away. Since Cumulocity Cloud cannot reach a broker running on `localhost`,
creating the connector as **enabled** would just produce a permanent
connection-failure loop in the logs. The workaround for local testing is:

1. Create the connector configuration **disabled**.
2. Start the dynamic-mapper-service **locally** (so it can actually reach
   `localhost:9093`).
3. Enable + connect the connector via the REST API once the local service is
   up.

## 1. Start Kafka locally

```bash
cd resources/test-env/kafka

# Start broker + Kafka UI
docker compose up -d

# Check status
docker compose ps

# Wait for the broker to report healthy (health check runs kafka-broker-api-versions.sh)
docker compose logs -f broker
```

### Access Points

- **Bootstrap servers (from the host / local dynamic-mapper-service)**: `localhost:9093`
- **Bootstrap servers (from another container on `kafka-network`)**: `broker:9092`
- **Kafka UI**: http://localhost:9090

> Only port `9093` (`PLAINTEXT_HOST`) is usable from the host machine. The
> `PLAINTEXT` listener on `9092` is advertised as `broker:9092`, which only
> resolves inside the `kafka-network` Docker network — it is not reachable
> from a dynamic-mapper-service running locally on the host.

### Stop Kafka

```bash
docker compose down        # stop and remove containers
docker compose down -v     # also remove the kafka-data volume (clean slate)
```

## 2. Create the Kafka connector — disabled

Configure the connector against the Cumulocity tenant the microservice will
run against, with `"enabled": false`. This can be done in the UI (Connectors
→ Add connector → Kafka), leaving the connector disabled after saving, or via
the REST API:

```bash
export C8Y_BASEURL="https://<your-tenant>.cumulocity.com"
export C8Y_TENANT="<tenant-id>"
export C8Y_USER="<user>"
export C8Y_PASSWORD="<password>"

curl -s -X POST "${C8Y_BASEURL}/service/dynamic-mapper-service/configuration/connector/instance" \
  -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}" \
  -H 'Content-Type: application/json' \
  -d '{
    "identifier": "test-kafka-connector",
    "connectorType": "KAFKA",
    "name": "Test Kafka Connector",
    "description": "Local Kafka test environment",
    "enabled": false,
    "properties": {
      "bootstrapServers": "localhost:9093",
      "username": "",
      "password": "",
      "groupId": "dynamic-mapper-test",
      "defaultPropertiesProducer": {},
      "defaultPropertiesConsumer": {}
    }
  }'
```

Or with go-c8y-cli:

```bash
c8y api --method POST --url /service/dynamic-mapper-service/configuration/connector/instance \
  --header 'Content-Type: application/json' \
  --data '{
    "identifier": "test-kafka-connector",
    "connectorType": "KAFKA",
    "name": "Test Kafka Connector",
    "description": "Local Kafka test environment",
    "enabled": false,
    "properties": {
      "bootstrapServers": "localhost:9093",
      "groupId": "dynamic-mapper-test"
    }
  }'
```

Since Cumulocity Cloud (where the connector config lives, but not where the
service runs yet) cannot reach `localhost:9093`, leaving `enabled: false` here
avoids a doomed connection attempt before the local service is even running.

## 3. Start the dynamic-mapper-service locally

Run the service with the `dev` profile, pointing at the Cumulocity tenant and
bootstrap user used above (see
`dynamic-mapper-service/src/main/resources/application-dev.properties`):

```bash
cd dynamic-mapper-service

export C8Y_BASEURL="https://<your-tenant>.cumulocity.com"
export C8Y_BOOTSTRAP_TENANT="<tenant-id>"
export C8Y_BOOTSTRAP_USER="servicebootstrap_dynamic-mapper-service"
export C8Y_BOOTSTRAP_PASSWORD="<bootstrap-password>"
export C8Y_MICROSERVICE_ISOLATION="MULTI_TENANT"

mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Wait until the service has subscribed to the tenant and picked up the
`test-kafka-connector` configuration created in step 2 (check the
Connectors page in the UI, or the service logs).

## 4. Enable the Kafka connector via REST API

With the service now running locally and able to reach `localhost:9093`,
flip `enabled` to `true` and connect it:

```bash
# Enable the connector (PUT the full config back with enabled: true)
curl -s -X PUT "${C8Y_BASEURL}/service/dynamic-mapper-service/configuration/connector/instance/test-kafka-connector" \
  -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}" \
  -H 'Content-Type: application/json' \
  -d '{
    "identifier": "test-kafka-connector",
    "connectorType": "KAFKA",
    "name": "Test Kafka Connector",
    "description": "Local Kafka test environment",
    "enabled": true,
    "properties": {
      "bootstrapServers": "localhost:9093",
      "groupId": "dynamic-mapper-test"
    }
  }'

# Trigger the actual connect operation
curl -s -X POST "${C8Y_BASEURL}/service/dynamic-mapper-service/operation" \
  -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}" \
  -H 'Content-Type: application/json' \
  -d '{"operation": "CONNECT", "parameter": {"connectorIdentifier": "test-kafka-connector"}}'

# Check status
curl -s "${C8Y_BASEURL}/service/dynamic-mapper-service/monitoring/status/connector/test-kafka-connector" \
  -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}"
```

Or with go-c8y-cli:

```bash
c8y api --method POST --url /service/dynamic-mapper-service/operation \
  --header 'Content-Type: application/json' \
  --data '{"operation": "CONNECT", "parameter": {"connectorIdentifier": "test-kafka-connector"}}'
```

The connector status should switch to `CONNECTED`. To go back to a disabled
state before stopping the local service, disconnect first
(`"operation": "DISCONNECT"`), then `PUT` the config again with
`"enabled": false`.

## Troubleshooting

### Broker never becomes healthy / kafka-ui never starts

`kafka-ui` waits for `condition: service_healthy` on the broker. Check the
health check directly:

```bash
docker exec broker /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092
```

### Connector can't connect from the local service

Confirm `bootstrapServers` is `localhost:9093`, not `localhost:9092` or
`broker:9092` — only `9093` is reachable from the host.

### Inspect topics / messages

Use the Kafka UI at http://localhost:9090, or exec into the broker:

```bash
docker exec -it broker /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
docker exec -it broker /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic <topic> --from-beginning
```

## Security Note

⚠️ **This setup has no authentication on the broker and is for local
development/testing only.**
