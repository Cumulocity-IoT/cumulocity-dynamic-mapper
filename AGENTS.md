# Cumulocity Dynamic Mapper — Agent Guidelines

Maps arbitrary JSON payloads between message brokers (MQTT, Kafka, HTTP, AMQP, Pulsar) and the Cumulocity IoT REST API in both directions (inbound and outbound), with a graphical or JavaScript-based mapping editor.

This file is an **index**. Read only the document relevant to your task — don't read everything.

---

## Module Structure

| Module | Purpose |
|--------|---------|
| `dynamic-mapper-interface` | Shared API interfaces/models — used by Java extensions |
| `dynamic-mapper-service` | Spring Boot microservice — main backend |
| `dynamic-mapper-extension` | Reference implementations for processor extensions |
| `dynamic-mapper-ui` | Angular frontend plugin for Cumulocity |
| `dynamic-mapper-smart-function` | TypeScript types, examples and tests for Smart Functions |

---

## Documentation Map

| Working on… | Start here |
|-------------|-----------|
| **System overview** — components, brokers, payload/transformation types, message flow | [ARCHITECTURE.md](ARCHITECTURE.md) |
| **Backend** — microservice packages, conventions, build/test | [docs/backend.md](docs/backend.md) |
| ↳ packages & message flow | [docs/backend/architecture.md](docs/backend/architecture.md) |
| ↳ thread safety, connectors, extensions, multi-tenancy | [docs/backend/conventions.md](docs/backend/conventions.md) |
| ↳ build & test | [docs/backend/build-test.md](docs/backend/build-test.md) |
| **Frontend** — Angular plugin structure, components, build/test | [docs/ui.md](docs/ui.md) |
| ↳ folder structure & services | [docs/ui/architecture.md](docs/ui/architecture.md) |
| ↳ drawer components & CSS conventions | [docs/ui/components.md](docs/ui/components.md) |
| ↳ build, deploy & test | [docs/ui/build-test.md](docs/ui/build-test.md) |
| **Smart Functions** — GraalVM JavaScript callbacks | [docs/smart-functions.md](docs/smart-functions.md) |
| **Extensions** — custom connectors & Java processor extensions | [EXTENSIONS.md](EXTENSIONS.md) |

---

## Quick Commands

```bash
# Backend (Java 21, Maven)
mvn clean package                                   # build all modules → deployable ZIP
cd dynamic-mapper-service && mvn test               # backend tests

# Frontend (Angular)
cd dynamic-mapper-ui && npm start                   # dev server
cd dynamic-mapper-ui && npm test                    # unit tests

# Smart Functions (TypeScript)
cd dynamic-mapper-smart-function && npm run build   # compile to JS
```

See the build/test sub-docs above for the full command set.

---

## Key Technologies

- Java 21, Spring Boot 3.3.5, Apache Camel 4.x
- GraalVM polyglot (`org.graalvm.polyglot`) — sandboxed JS for Smart Functions
- JSONata (`com.dashjoin:jsonata`) — expression language for substitutions
- Cumulocity Microservice SDK (`c8y.version=2026.13.0`)
- Angular + `@c8y/ngx-components` — Cumulocity web plugin framework
