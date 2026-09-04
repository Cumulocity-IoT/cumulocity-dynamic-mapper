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

### Logging the local service to a file

By default the service only logs to the console (or the IDE's debug console),
which isn't easy to `grep`/`tail` after the fact. File logging is opt-in and
off by default — including in this profile — so it never accidentally writes
files in production. Enable it by setting the `LOCAL_LOG_FILE` JVM system
property (works the same via `mvn spring-boot:run` or an IDE launch
configuration's VM arguments):

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-DLOCAL_LOG_FILE=/tmp/dynamic-mapper.log"
```

Or as a VM argument in a `launch.json` debug configuration:

```json
{
    "type": "java",
    "name": "Spring Boot-App<dynamic-mapper-service>",
    "request": "launch",
    "mainClass": "dynamic.mapper.App",
    "projectName": "dynamic-mapper-service",
    "vmArgs": "-DLOCAL_LOG_FILE=/tmp/dynamic-mapper.log"
}
```

This writes a rolling log file (50MB per file, 3 days of history) at the path
you choose, in addition to the normal console output. Leave `LOCAL_LOG_FILE`
unset for normal runs — with it unset, no file appender is created at all
(see `logback-spring.xml`).

## 4. Publish test messages

`KafkaTestClient`
(`dynamic-mapper-service/src/test/java/dynamic/mapper/client/KafkaTestClient.java`)
is a standalone producer for exercising the connector without a real device.
Run it with Maven against the **test** classpath (it lives under
`src/test/java`, so it isn't on the main classpath the packaged service runs
from):

```bash
cd dynamic-mapper-service
KAFKA_BROKER_HOST=localhost:9093 SECURITY_PROTOCOL=PLAINTEXT TOPIC=measurement \
  mvn exec:java -Dexec.mainClass=dynamic.mapper.client.KafkaTestClient -Dexec.classpathScope=test
```

`SECURITY_PROTOCOL=PLAINTEXT` matches this local broker (no auth). See the
class's Javadoc for the full list of environment variables (`BROKER_USERNAME`
/ `BROKER_PASSWORD` / `SASL_MECHANISM` are only needed against a real,
authenticated broker).

## 5. Enable the Kafka connector via REST API

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

## 6. Test with Message Explorer

The Message Explorer page in the browser UI **cannot** be used against a
locally-running service: the browser talks to Cumulocity Cloud, whose gateway
validates your session and forwards to the *deployed* microservice — a bare
`localhost:8080` process never sees that traffic, and pointing the UI's dev
proxy directly at `localhost:8080` breaks the session/OAuth handling the
gateway normally provides, breaking the rest of the UI in the process. Drive
Message Explorer directly via REST against the local port instead:

```bash
# Start a session (INBOUND captures broker -> C8Y; connectorIdentifier is the
# connector's "identifier", e.g. "test-kafka-connector" from step 2)
curl -s -X POST "http://localhost:8080/explorer/session" \
  -u "${C8Y_BOOTSTRAP_TENANT}/${C8Y_BOOTSTRAP_USER}:${C8Y_BOOTSTRAP_PASSWORD}" \
  -H 'Content-Type: application/json' \
  -d '{"connectorIdentifier": "test-kafka-connector", "topic": "measurement", "direction": "INBOUND", "maxMessages": 50}'
# -> {"sessionId":"<uuid>"}

# Publish a message (step 4), then poll for it
curl -s "http://localhost:8080/explorer/session/<sessionId>/messages" \
  -u "${C8Y_BOOTSTRAP_TENANT}/${C8Y_BOOTSTRAP_USER}:${C8Y_BOOTSTRAP_PASSWORD}"

# Stop the session when done
curl -s -X DELETE "http://localhost:8080/explorer/session/<sessionId>" \
  -u "${C8Y_BOOTSTRAP_TENANT}/${C8Y_BOOTSTRAP_USER}:${C8Y_BOOTSTRAP_PASSWORD}"
```

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

### No consumer group ever appears / messages never arrive, with no error anywhere

This is a broker bootstrap issue, not a connector or explorer bug. Check the
broker's own logs:

```bash
docker logs broker | grep "consumer_offsets"
```

If you see repeated `Auto topic creation failed for __consumer_offsets with
error 'INVALID_REPLICATION_FACTOR' ... only 1 broker(s) are registered`, the
internal `__consumer_offsets` topic — required by **every** Kafka consumer
group, mapping-driven or Message Explorer session — can never be created,
because it defaults to replication factor 3 and this is a single-node broker.
`docker-compose.yaml` already sets `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1`
to fix this; if you've copied/modified the compose file and lost that
setting, add it back and recreate the broker (`docker compose down && docker
compose up -d` — this only removes containers, not the `kafka-data` volume).
Because no coordinator can ever be found, the Kafka client's `subscribe()`
call itself never throws — it retries `FindCoordinator` silently forever, so
nothing in the dynamic-mapper-service logs will point at this; the broker log
is the only place it's visible.

## Security Note

⚠️ **This setup has no authentication on the broker and is for local
development/testing only.**
