# UI Architecture

Angular app under `dynamic-mapper-ui/src/`, built as a Cumulocity web plugin with `@c8y/ngx-components`.

## Folder Structure

| Folder | Purpose |
|--------|---------|
| `mapping/` | Mapping feature: stepper wizard, substitution editor, testing, grid view |
| `connector/` | Connector configuration UI |
| `monitoring/` | Real-time mapper and connector status |
| `configuration/` | Service/tenant configuration |
| `shared/` | Common services, models, API path constants |

## Key Services

- `mapping/service/` — API calls for mapping CRUD and testing
- `connector/` — connector configuration API
- `TestingService` — executes mapping tests via the backend endpoint (`PATH_TESTING_ENDPOINT`); all testing is done server-side

## Mapping Direction

- **Inbound** = Broker → C8Y
- **Outbound** = C8Y → Broker. `filterMapping` is a JSONata expression required on all outbound mappings (default `'true'`).
