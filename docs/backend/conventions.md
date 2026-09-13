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

The service runs as an enterprise microservice subscribed to many sub-tenants **inside one JVM**.
Almost every service is a Spring singleton, so tenant isolation is a property of how state is
keyed and cleaned up, not something the framework gives you.

### Three rules

**1. No shared mutable state for tenant data.** A `static` field holding tenant data is observed
by every tenant at once. If you need a per-tenant instance of something, expose a *factory*, not
a constant — `MappingStatus.createUnspecified()` rather than a shared
`UNSPECIFIED_MAPPING_STATUS`. A `static` sentinel that is only ever *read* (e.g.
`Mapping.UNSPECIFIED_MAPPING`, used to look up a status by identifier) is fine, but make it
`final` and say so in its javadoc.

**2. Key every store by tenant, including composite keys.** Two shapes are in use, both fine:

- nested — `Map<String, Map<String, T>>`, tenant first (`MappingCacheManager`, `MappingStatusService`)
- prefixed — a flat map with `"tenant|…"` keys (`TenantRegistry`'s external-ID cache)

What is *not* fine is keying by a Cumulocity id alone. Managed-object ids, device ids and
customer-chosen external ids are only unique **within** a tenant — `"12345"` and
`"c8y_Serial/sensor-1"` exist in many tenants at once.

**3. Everything keyed by tenant must be removed on unsubscribe.** `BootstrapService.cleanTenantResources()`
is the single place this happens; a store that is not reached from there grows for the lifetime of
the process, and — worse — a tenant that unsubscribes and re-subscribes inherits stale state, e.g.
external IDs resolving to managed objects deleted in the meantime.

When you add a tenant-keyed map, add its removal in the same change. Currently cleaned:
notification connections, connector clients and registry entries, service configuration,
mapper service representation, microservice credentials, GraalVM engines/contexts, extensions,
mapping cache + status + deployment map + flow state, the external-ID and inventory caches,
the external-ID resolution cache and its per-ID locks, and the processing-mode connector cache.

### Credentials deserve extra care

Anything holding a tenant's service-user credentials — a `RestConnector`, a token, a client — is
the worst thing to get wrong, because a mix-up executes one tenant's work against another's data.
Two specific traps:

- **Build and key from the same source.** `ProcessingModeService` caches a `RestConnector` per
  tenant but builds it from the *ambient* `contextService.getContext()`. That is only correct
  while the key and the context agree; when they cannot, it must not cache
  (see `getOrCreateConnector`).
- **Do not let credentials outlive the subscription.** Cached clients are dropped in
  `cleanTenantResources()`.

### Tests

`TenantRegistryIsolationTest`, `ProcessingModeServiceTenantTest`, `MultiTenancyIsolationTest` and
`MappingStatusTest` cover the isolation and cleanup contracts — including that the *same*
external ID and the *same* internal id in two tenants stay separate. Add a case there when you
add a tenant-keyed store.

## Mapping Direction

- **INBOUND** = Broker → C8Y
- **OUTBOUND** = C8Y → Broker. `filterMapping` is a JSONata expression required on all outbound mappings (default `'true'`).
