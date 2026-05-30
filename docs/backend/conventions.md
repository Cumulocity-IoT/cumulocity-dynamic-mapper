# Backend Critical Conventions

## Thread Safety — ProcessingContext sub-contexts

`ProcessingContext` is the per-message state. It is decomposed into focused sub-contexts, each with its own thread-safety guarantee:

| Context class | Role | Thread-safety rule |
|--------------|------|-------------------|
| `RoutingContext` | topic, tenant, qos, api, clientId | Immutable — safe to share |
| `PayloadContext<T>` | raw + deserialized payload | Immutable — safe to share |
| `DeviceContext` | sourceId, externalId, device info | Copy-on-write |
| `ProcessingState` | flags, cache | `ConcurrentHashMap` / `AtomicBoolean` |
| `OutputCollector` | collected C8Y requests, errors, logs | `CopyOnWriteArrayList` |
| `ExecutionContext` | GraalVM context | Per-thread — **must use `try-with-resources`** |

> **Always** wrap `ExecutionContext` in `try-with-resources` to prevent GraalVM memory leaks.

**Rules:**
- For parallel processing, **always** use focused contexts; never mutate `ProcessingContext` directly.
- Prefer focused context parameters over the full `ProcessingContext` in method signatures — makes dependencies explicit and improves testability.

```java
// Extract focused contexts and pass only what a method needs
RoutingContext routing = context.getRoutingContext();
OutputCollector output  = context.getOutputCollector();
processMessage(routing, output);
```

## Adding a New Connector

1. Extend `AConnectorClient` and implement the broker lifecycle methods (`initialize()`, `connect()`, `subscribe()`, `disconnect()`, `publishMEAO()`).
2. Provide a `ConnectorSpecification` declaring the configuration schema.
3. Register via `ConnectorRegistry`.
4. Implement a callback that forwards broker messages to `GenericMessageCallback`.

See [EXTENSIONS.md](../../EXTENSIONS.md) for the full guide and `AConnectorClient` helper methods.

## Adding a Java Extension

Implement `ProcessorExtensionInbound<O>` or `ProcessorExtensionOutbound<O>` from `dynamic-mapper-interface`. These receive `DataPrepContext` (**not** `ProcessingContext`) as the method parameter. See `dynamic-mapper-extension/` for reference implementations and [EXTENSIONS.md](../../EXTENSIONS.md) for the full guide.

## Multi-tenancy

`ConfigurationRegistry` and `C8YAgent` are scoped **per-tenant**. Never use static/singleton state for tenant data. The service can run as an enterprise microservice subscribed to multiple sub-tenants, each with isolated connector configurations, mappings, and processing state.

## Mapping Direction

- **INBOUND** = Broker → C8Y
- **OUTBOUND** = C8Y → Broker. `filterMapping` is a JSONata expression required on all outbound mappings (default `'true'`).
