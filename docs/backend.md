# Backend — `dynamic-mapper-service`

The Spring Boot microservice that maps data between message brokers and the Cumulocity IoT REST API in both directions. It exposes REST endpoints, hosts the broker connectors, and runs the transformation engines (JSONata, Smart Functions, Java extensions).

Read the sub-document that matches your task:

| Document | Read when you need to… |
|----------|------------------------|
| [architecture.md](backend/architecture.md) | Understand key packages, the message-processing flow, and transformation types |
| [conventions.md](backend/conventions.md) | Follow thread-safety rules, add a connector, add a Java extension, or work with multi-tenancy |
| [build-test.md](backend/build-test.md) | Build the service or run backend tests |

Related:
- [EXTENSIONS.md](../EXTENSIONS.md) — full guide for custom connectors and Java processor extensions
- [smart-functions.md](smart-functions.md) — Smart Function (GraalVM JavaScript) development
- [ARCHITECTURE.md](../ARCHITECTURE.md) — system-level component overview
