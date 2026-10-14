# Backend Architecture

The backend is the `dynamic-mapper-service` Spring Boot microservice. See [architecture.md](../architecture.md) for the full system-level component overview and key technology versions.

## Key Packages

Under `dynamic-mapper-service/src/main/java/dynamic/mapper/`:

| Package | Role |
|---------|------|
| `connector/` | Broker connector implementations (`mqtt/`, `kafka/`, `http/`, `amqp/`, `pulsar/`, `webhook/`) |
| `connector/core/client/AConnectorClient.java` | Abstract base for all connectors |
| `connector/core/ConnectorSpecification.java` | Declares connector properties/configuration schema |
| `processor/inbound/InboundMessageDispatcher.java` | Entry point for Broker → C8Y; resolves mappings, applies the pipeline timeout, submits to `InboundMessageRouter` on a virtual thread |
| `processor/inbound/InboundMessageRouter.java` | Per-mapping inbound processor chain |
| `processor/outbound/OutboundMessageDispatcher.java` | Entry point for C8Y → Broker (with `OutboundMessageRouter`) |
| `processor/runtime/ProcessingContext.java` | Per-message processing state (see [conventions.md](conventions.md)) |
| `processor/flow/JavaScriptProcessor.java` | GraalVM JS execution for Smart Functions |
| `core/C8YAgent.java` | Cumulocity REST API client (inventory, measurements, events, alarms) |
| `core/ConfigurationRegistry.java` | Central service registry (per-tenant) |
| `mapping/MappingService.java` | Mapping CRUD and lookup |

## Message Flow

**Inbound** (Broker → C8Y):

```
AConnectorClient → InboundMessageDispatcher → InboundMessageRouter → deserialize → enrich → substitute/eval → emit to C8Y
```

**Outbound** (C8Y → Broker): entry point is `OutboundMessageDispatcher` (hands off to `OutboundMessageRouter`).

Abstract processor hierarchy:
`AbstractEnrichmentProcessor` → `AbstractFlowProcessor` / `AbstractJSONataExtractionProcessor` / `AbstractExtensibleProcessor`

## Transformation Types

| Type | Description |
|------|-------------|
| **JSONata** | Expression language evaluated by `com.dashjoin:jsonata` |
| **Smart Functions** | JavaScript executed in a GraalVM polyglot sandbox — see [smart-functions.md](../smart-functions.md) |
| **Extensions** | Java `ProcessorExtensionInbound<O>` / `ProcessorExtensionOutbound<O>` plugins — see [extensions.md](../extensions.md) |

Key technologies and exact version pins are listed once, in [architecture.md](../architecture.md#key-technologies).
