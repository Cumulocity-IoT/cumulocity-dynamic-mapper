# Cumulocity Dynamic Mapper — Agent Guidelines

Maps arbitrary JSON payloads between message brokers (MQTT, Kafka, HTTP, AMQP, Pulsar) and the Cumulocity IoT REST API in both directions (inbound and outbound), with a graphical or JavaScript-based mapping editor.

See [ARCHITECTURE.md](ARCHITECTURE.md) for a full component overview.

---

## Module Structure

| Module | Purpose |
|--------|---------|
| `dynamic-mapper-interface` | Shared API interfaces/models — used by Java extensions |
| `dynamic-mapper-service` | Spring Boot microservice — main backend |
| `dynamic-mapper-extension` | Reference implementations for processor extensions |
| `dynamic-mapper-ui` | Angular frontend plugin for Cumulocity |

---

## Build & Test

### Backend (Java)

```bash
# Build all modules (produces deployable ZIP in dynamic-mapper-service/target/)
mvn clean package

# Build a single module
cd dynamic-mapper-service && mvn clean package

# Run all tests
cd dynamic-mapper-service && mvn test

# Run a specific test class or method
cd dynamic-mapper-service && mvn test -Dtest=GraalVMTest
cd dynamic-mapper-service && mvn test -Dtest=GraalVMTest#testMethod
```

### Frontend (Angular)

```bash
cd dynamic-mapper-ui

npm start          # dev server with live reload
npm run build      # production build
npm run deploy     # deploy to Cumulocity tenant (requires env vars)
npm test           # unit tests
npm run lint       # lint
```

---

## Backend Architecture

**Key packages** under `dynamic-mapper-service/src/main/java/dynamic/mapper/`:

| Package | Role |
|---------|------|
| `connector/` | Broker connector implementations (`mqtt/`, `kafka/`, `http/`, `amqp/`, `pulsar/`) |
| `connector/core/client/AConnectorClient.java` | Abstract base for all connectors |
| `processor/inbound/CamelDispatcherInbound.java` | Apache Camel entry point for Broker → C8Y |
| `processor/outbound/CamelDispatcherOutbound.java` | Entry point for C8Y → Broker |
| `processor/model/ProcessingContext.java` | Per-message processing state |
| `core/C8YAgent.java` | Cumulocity REST API client |
| `core/ConfigurationRegistry.java` | Central service registry (per-tenant) |
| `service/MappingService.java` | Mapping CRUD and lookup |
| `processor/flow/JavaScriptProcessor.java` | GraalVM JS execution for Smart Functions |

**Message flow (inbound):**
`AConnectorClient` → `CamelDispatcherInbound` → deserialize → snoop → enrich → substitute/eval → emit to C8Y

**Transformation types:**
- **JSONata** — expression language (`com.dashjoin:jsonata`)
- **Smart Functions** — JavaScript in GraalVM polyglot sandbox
- **Extensions** — Java `ProcessorExtensionInbound<O>` plugins

---

## Critical Conventions

### Thread Safety — ProcessingContext sub-contexts

| Context class | Thread-safety rule |
|--------------|-------------------|
| `RoutingContext` | Immutable — safe to share |
| `PayloadContext<T>` | Immutable — safe to share |
| `DeviceContext` | Copy-on-write |
| `ProcessingState` | `ConcurrentHashMap` / `AtomicBoolean` |
| `OutputCollector` | `CopyOnWriteArrayList` |
| `ExecutionContext` | Per-thread — **must use `try-with-resources`** |

> **Always** wrap `ExecutionContext` in `try-with-resources` to prevent GraalVM memory leaks.

### Adding a New Connector

1. Extend `AConnectorClient` and provide a `ConnectorSpecification`
2. Register via `ConnectorRegistry`

### Adding a Java Extension

Implement `ProcessorExtensionInbound<O>` or `ProcessorExtensionOutbound<O>` from `dynamic-mapper-interface`.
These receive `DataPrepContext` (not `ProcessingContext`) as the method parameter.
See `dynamic-mapper-extension/` for reference. See [EXTENSIONS.md](EXTENSIONS.md) for the full guide.

### Multi-tenancy

`ConfigurationRegistry` and `C8YAgent` are scoped **per-tenant**. Never use static/singleton state for tenant data.

---

## Frontend Architecture

Angular app under `dynamic-mapper-ui/src/`:

| Folder | Purpose |
|--------|---------|
| `mapping/` | Mapping feature: stepper wizard, substitution editor, testing, grid |
| `connector/` | Connector configuration UI |
| `monitoring/` | Real-time mapper and connector status |
| `configuration/` | Service/tenant configuration |
| `shared/` | Common services, models, API path constants |

### Drawer Components

Components opened via `BottomDrawerService.openDrawer()` **must** have this on their `@Component` decorator:

```ts
host: { class: 'flex-grow d-col fit-h' }
```

Standard drawer template structure:
```html
<div class="d-col flex-nowrap no-align-items p-48 flex-grow col-md-12 col-md-offset-0 c8y-stepper--no-btns">
  <div class="card card--fullpage d-col flex-grow">
    <div class="card-header separator j-c-center"> ... </div>
    <div class="card-inner-scroll flex-grow"> ... </div>
    <div class="card-footer separator p-24 text-center flex-no-shrink">
      <!-- buttons -->
    </div>
  </div>
</div>
```

Key CSS classes: `d-col` = flex column (not `flex-col` which lacks `display:flex`), `flex-grow`, `fit-h`, `flex-no-shrink`.

### Mapping Direction Enum

Inbound = Broker → C8Y. Outbound = C8Y → Broker.
`filterMapping` is a JSONata expression required on all OUTBOUND mappings (default: `'true'`).

---

## Key Technologies

- Java 21, Spring Boot 3.3.5, Apache Camel 4.x
- GraalVM polyglot (`org.graalvm.polyglot`) — sandboxed JS for Smart Functions
- JSONata (`com.dashjoin:jsonata`) — expression language for substitutions
- Cumulocity Microservice SDK (`c8y.version=2026.13.0`)
- Angular + `@c8y/ngx-components` — Cumulocity web plugin framework
