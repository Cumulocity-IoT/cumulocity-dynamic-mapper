# Cumulocity Dynamic Mapper

Map arbitrary broker payloads to and from the Cumulocity domain model using a graphical editor or JavaScript-based mappings.

## What It Does

The Dynamic Mapper connects to external brokers and APIs, subscribes to topics, transforms payloads, and routes data bidirectionally:

- **Inbound:** Broker -> Cumulocity
- **Outbound:** Cumulocity -> Broker/API

It supports zero-code mapping, code-based mapping, and AI-assisted mapping suggestions.

<p align="center">
  <img src="resources/image/Dynamic_Mapper_Mapping_Stepper_Substitution_Basic.png" style="width: 80%;" />
</p>

## Core Features

- Connect multiple brokers/connectors at the same time
- Create bidirectional mappings between broker payloads and Cumulocity APIs
- Build mappings in a graphical editor or with JavaScript
- Transform payloads with [JSONata](https://jsonata.org/) or JavaScript
- Filter by topics and expressions
- Explore live traffic via Message Explorer
- Use AI agents to propose mapping definitions from observed/provided payloads
- Run in multi-tenant environments

## Supported Connectors

| Connector | Purpose |
|---|---|
| AMQP 0-9-1 | Connect to brokers like RabbitMQ |
| AMQP 1.0 | Connect to AMQP 1.0 systems (Azure Service Bus, Artemis, Solace, etc.) |
| Apache Kafka | Integrate with Kafka topics |
| Apache Pulsar | Integrate with Pulsar topics |
| Cumulocity API | Create/update/delete managed objects, events, alarms, measurements |
| Cumulocity MQTT Service | Use Cumulocity's built-in MQTT broker with device isolation |
| Google Cloud Pub/Sub | Publish/subscribe data via a Google Cloud Pub/Sub topic and subscription, e.g. for ingestion into Google's Manufacturing Data Engine (MDE) |
| HTTP Connector | Receive payloads via REST endpoints |
| MQTT Broker | Connect to third-party MQTT brokers (HiveMQ, Mosquitto, etc.) |
| Webhook | Forward data to external REST APIs |

## Documentation

Everything beyond this page, `CHANGES.md`, and `FAQ.md` lives under [`docs/`](docs/) — see
[`docs/README.md`](docs/README.md) for the full index. Frequently needed pages:

- [Architecture Overview](docs/architecture.md)
- [Installation Guide](docs/installation.md)
- User Guide — the full walkthrough of using the deployed app lives in the app itself (Help menu),
  built from [`dynamic-mapper-ui/public/docs`](dynamic-mapper-ui/public/docs)
- [FAQ](FAQ.md)
- [Limitations](docs/limitations.md)
- [Extensions Guide](docs/extensions.md)
- [Backend Docs](docs/backend.md)
- [Frontend Docs](docs/ui.md)
- [Smart Functions Docs](docs/smart-functions.md)
- [Test Concept](docs/testing.md)

## API

- REST API docs: [resources/openAPI/README.md](resources/openAPI/README.md)
- OpenAPI spec: [resources/openAPI/openapi.json](resources/openAPI/openapi.json)
- Swagger UI (runtime): `{yourTenantURL}/service/dynamic-mapper-service/swagger-ui/index.html`

## Build and Test

Project modules and common commands are documented in [AGENTS.md](AGENTS.md) and module docs.

```bash
# Build all modules (from repo root)
mvn clean package

# Backend tests
cd dynamic-mapper-service
mvn test

# Frontend dev / tests
cd ../dynamic-mapper-ui
npm start
npm test
```

## Test Assets and Sample Data

### Load Test

- JMeter profile: [resources/testing/performance/jmeter/jmeter_test_01.jmx](resources/testing/performance/jmeter/jmeter_test_01.jmx)
- MQTT JMeter extension: [emqx/mqtt-jmeter](https://github.com/emqx/mqtt-jmeter)
- Python MQTT load generators: [resources/testing/performance/mqtt/](resources/testing/performance/mqtt/)
- See [resources/testing/performance/README.md](resources/testing/performance/README.md) for how to run them.

### Sample Mappings

Import and export mappings from the **Inbound Mappings** / **Outbound Mappings** table in the UI, individually
or all at once — there is no longer a standalone import script. Ready-made sets to start from:

- [resources/samples/mappings-INBOUND.json](resources/samples/mappings-INBOUND.json)
- [resources/samples/mappings-OUTBOUND.json](resources/samples/mappings-OUTBOUND.json)
- Documented overview: [resources/samples/SampleMappings_21.xlsx](resources/samples/SampleMappings_21.xlsx)
  (also as PDF, [inbound](resources/samples/SampleMappings_INBOUND_21.pdf) /
  [outbound](resources/samples/SampleMappings_OUTBOUND_21.pdf))

## Security Notes for Code-Based Mappings (JavaScript)

User-defined JavaScript is executed inside the backend JVM with sandbox restrictions.
The GraalVM context is configured to minimize guest-code access to host resources.

The sandbox behavior has been validated with examples such as:

- Accessing `process.env` from JavaScript (blocked)
- Accessing host class `java.lang.System` via `Java.type(...)` (blocked)

Reference: [GraalVM Sandboxing](https://www.graalvm.org/latest/security-guide/sandboxing/)

---

These tools are provided as-is and without warranty or support. They do not constitute part of the product suite. Users are free to use, fork and modify them, subject to the license agreement. While we welcome contributions, we cannot guarantee to include every contribution in the main project.

For more information, see [cumulocity.com](https://www.cumulocity.com) or ask a question in the [Cumulocity Community](https://community.cumulocity.com/).
