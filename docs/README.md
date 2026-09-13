# Documentation

Technical documentation for building, extending, and operating the Dynamic Mapper. If you're
looking for how to *use* the deployed app, that lives in the app itself (Help menu), built from
[`dynamic-mapper-ui/public/docs`](../dynamic-mapper-ui/public/docs).

## For anyone deploying or operating it

| Page | Covers |
|---|---|
| [installation.md](installation.md) | Prerequisites, roles/permissions, deploying the microservice and the web app plugin |
| [limitations.md](limitations.md) | Supported C8Y APIs, JSONata engine differences, transformation type overview |
| [architecture.md](architecture.md) | System-level component overview, module structure, message flow |

## For developers and agents working on the code

| Page | Covers |
|---|---|
| [backend.md](backend.md) | Entry point for `dynamic-mapper-service` — links to architecture, conventions, build/test |
| [ui.md](ui.md) | Entry point for `dynamic-mapper-ui` — links to architecture, components, build/test |
| [extensions.md](extensions.md) | Full guide for adding a custom broker connector or a Java processor extension, with code samples |
| [smart-functions.md](smart-functions.md) | Smart Function (GraalVM JavaScript) development, for both backend and mapping-editor context |
| [smart-function-type-sync.md](smart-function-type-sync.md) | Keeping the Smart Function TypeScript types in sync with the runtime API |
| [testing.md](testing.md) | Test strategy across all four layers (backend, frontend, system/shell, Smart Function module) |
| [feature/README.md](feature/README.md) | One page per feature, split into Requirements (the contract) and Implementation (how it's built) |
| [planning/](planning/) | Point-in-time implementation plans and requirements docs for larger features |

Related, kept at repo root by tool/GitHub convention rather than moved here: `CLAUDE.md` and
`AGENTS.md` (agent-facing indexes), `CONTRIBUTING.md`.
