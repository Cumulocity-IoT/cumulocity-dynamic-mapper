# Cumulocity Dynamic Mapper — Agent Guidelines

Maps arbitrary JSON payloads between message brokers (MQTT, Kafka, HTTP, AMQP, Pulsar) and the Cumulocity IoT REST API in both directions (inbound and outbound), with a graphical or JavaScript-based mapping editor.

This file is an **index**. Read only the document relevant to your task — don't read everything.
Module structure, key technologies and exact version pins live in one place —
[docs/architecture.md](docs/architecture.md) — rather than repeated here, so they don't drift out
of sync with it (they had: this file previously claimed Java 21 / Spring Boot 3.3.5 / c8y 2026.13.0
where `pom.xml` actually pins Java 25 / Spring Boot 4.0.7 / c8y 2026.46.0).

---

## Documentation Map

| Working on… | Start here |
|-------------|-----------|
| **System overview** — components, brokers, payload/transformation types, message flow | [docs/architecture.md](docs/architecture.md) |
| **Backend** — microservice packages, conventions, build/test | [docs/backend.md](docs/backend.md) |
| ↳ packages & message flow | [docs/backend/architecture.md](docs/backend/architecture.md) |
| ↳ thread safety, connectors, extensions, multi-tenancy | [docs/backend/conventions.md](docs/backend/conventions.md) |
| ↳ build & test | [docs/backend/build-test.md](docs/backend/build-test.md) |
| **Frontend** — Angular plugin structure, components, build/test | [docs/ui.md](docs/ui.md) |
| ↳ folder structure & services | [docs/ui/architecture.md](docs/ui/architecture.md) |
| ↳ drawer components & CSS conventions | [docs/ui/components.md](docs/ui/components.md) |
| ↳ build, deploy & test | [docs/ui/build-test.md](docs/ui/build-test.md) |
| **Smart Functions** — GraalVM JavaScript callbacks | [docs/smart-functions.md](docs/smart-functions.md) |
| **Extensions** — custom connectors & Java processor extensions | [docs/extensions.md](docs/extensions.md) |
| **Installing / deploying** | [docs/installation.md](docs/installation.md) |
| **Test strategy** — all four layers | [docs/testing.md](docs/testing.md) |
| Everything else | [docs/README.md](docs/README.md) |

---

## Quick Commands

```bash
# Backend (Maven — see docs/architecture.md for the exact Java version)
mvn clean package                                   # build all modules → deployable ZIP
cd dynamic-mapper-service && mvn test               # backend tests

# Frontend (Angular)
cd dynamic-mapper-ui && npm start                   # dev server
cd dynamic-mapper-ui && npm test                    # unit tests

# Smart Functions (TypeScript)
cd dynamic-mapper-smart-function && npm run build   # compile to JS
```

See the build/test sub-docs above for the full command set.
