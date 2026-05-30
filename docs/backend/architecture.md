# Backend Architecture

The backend is the `dynamic-mapper-service` Spring Boot microservice. See [ARCHITECTURE.md](../../ARCHITECTURE.md) for the full system-level component overview.

## Key Packages

Under `dynamic-mapper-service/src/main/java/dynamic/mapper/`:

| Package | Role |
|---------|------|
| `connector/` | Broker connector implementations (`mqtt/`, `kafka/`, `http/`, `amqp/`, `pulsar/`, `webhook/`) |
| `connector/core/client/AConnectorClient.java` | Abstract base for all connectors |
| `connector/core/ConnectorSpecification.java` | Declares connector properties/configuration schema |
| `processor/inbound/CamelDispatcherInbound.java` | Apache Camel entry point for Broker → C8Y |
| `processor/outbound/CamelDispatcherOutbound.java` | Entry point for C8Y → Broker |
| `processor/model/ProcessingContext.java` | Per-message processing state (see [conventions.md](conventions.md)) |
| `processor/flow/JavaScriptProcessor.java` | GraalVM JS execution for Smart Functions |
| `core/C8YAgent.java` | Cumulocity REST API client (inventory, measurements, events, alarms) |
| `core/ConfigurationRegistry.java` | Central service registry (per-tenant) |
| `service/MappingService.java` | Mapping CRUD and lookup |

## Message Flow

**Inbound** (Broker → C8Y):

```
AConnectorClient → CamelDispatcherInbound → deserialize → snoop → enrich → substitute/eval → emit to C8Y
```

**Outbound** (C8Y → Broker): entry point is `CamelDispatcherOutbound`.

Abstract processor hierarchy:
`AbstractSnoopingProcessor` → `AbstractEnrichmentProcessor` → `AbstractCodeExtractionProcessor` / `AbstractJSONataExtractionProcessor` / `AbstractExtensibleProcessor`

## Transformation Types

| Type | Description |
|------|-------------|
| **JSONata** | Expression language evaluated by `com.dashjoin:jsonata` |
| **Smart Functions** | JavaScript executed in a GraalVM polyglot sandbox — see [smart-functions.md](../smart-functions.md) |
| **Extensions** | Java `ProcessorExtensionInbound<O>` / `ProcessorExtensionOutbound<O>` plugins — see [EXTENSIONS.md](../../EXTENSIONS.md) |

## Key Technologies

- Java 21, Spring Boot 3.3.5, Apache Camel 4.x (internal message routing)
- GraalVM polyglot (`org.graalvm.polyglot`) — sandboxed JS for Smart Functions
- JSONata (`com.dashjoin:jsonata`) — expression language for substitutions
- Cumulocity Microservice SDK (`c8y.version=2026.13.0`)
