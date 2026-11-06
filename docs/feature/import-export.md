# Import and export

Mappings, connectors, and the service configuration can each be exported to a JSON file and imported
from one. The three work differently, because they hold different kinds of data. Moving between tenants,
restoring after a reset, and sharing samples all use these files.

---

## Requirements

**What it is for.** Copying configuration between tenants or into a fresh one, and keeping a snapshot to
restore after a reset.

### Common rules

- **A file holds one kind of data.** Mappings and connectors are JSON arrays, the service configuration is
  a single JSON object. Importing the wrong kind is refused with a message that names the mix-up (service
  configuration) or fails (mappings, connectors).
- **Import is a manual, UI-only action.** There is no import or export endpoint in the service.
- **Import never switches anything on.** Imported mappings are inactive and imported connectors are
  disabled.
- **Deployment is not part of any file.** After an import, a mapping is deployed nowhere and has to be
  deployed to connectors again ([deployment-map.md](deployment-map.md)).

### Mappings

- **Export** covers all mappings of the selected direction, the selected mappings, or one mapping. The file is
  the mapping definitions including Smart Function code.
- **Import is additive.** Every mapping in the file is created as a new mapping with a new identifier and
  inactive. Existing mappings are never changed or replaced, so importing the same file twice creates
  duplicates.
- **Each mapping is validated like a mapping saved by hand.** One invalid mapping does not stop the others.
  The result lists successes and failures by mapping name.
- **Direction is taken from the file.** A file of outbound mappings imported on the inbound page creates
  outbound mappings.
- **Who:** Create or Admin.

### Connectors

- **Export** covers all connectors, selected connectors, or one connector. The Default HTTP Connector is never
  exported.
- **Secrets are not exported.** Passwords, tokens, and similar properties are written as `****`.
- **Import is additive and creates disabled connectors** with new identifiers. A connector whose file holds
  masked secrets has to be opened and given the real values before it is enabled; the user is told so.
- **The Default HTTP Connector is skipped** on import, with a notice, because the service creates it itself.
- **Who:** Admin.

### Service configuration

- **Export is a snapshot of the whole configuration,** including the code templates (see
  [code-templates.md](code-templates.md)). It holds no credentials.
- **Import replaces settings.** The user sees how many settings and templates the file holds and confirms before
  anything is applied. Settings missing from the file stay as they are; the code template map in the file replaces
  the stored one.
- **Settings that need a re-subscribe still need it.** Importing does not apply the outbound-mapping or the device
  isolation switch to a running service ([device-isolation.md](device-isolation.md)).
- **Who:** Admin.

**Non-goals.** No merge or update-in-place of existing mappings and connectors, no selective import,
no import of deployment, status, version history, or drafts, no cross-version migration of files.

---

## Implementation

### Mappings

| Step | Code |
|---|---|
| Export | `MappingComponent.exportMappings` (and the bulk path in `MappingBulkOperationsService`) writes `serializeMappingsForExport(mappings)` to `mappings-<direction>.json`. The objects are the `Mapping` records as the service returns them, without `id` and `identifier`: the import assigns new values for both. |
| Import dialog | [`ImportMappingsComponent`](../../dynamic-mapper-ui/src/mapping/import/import-mappings.component.ts) |
| Per mapping | Sets `identifier = createCustomUuid()`, `lastUpdate = now`, `active = false`, then `POST /mapping` ([`MappingService.createMapping`](../../dynamic-mapper-ui/src/mapping/core/mapping.service.ts)). Failures are collected by name. |
| Service side | `MappingController.createMapping` forces `active = false` again, validates, and calls `MappingService.createMapping`, which keeps the version and version note from the file so an imported v4 stays v4. |
| After | The dialog's close handler calls `refreshMappings` for the current direction. |

The import button is disabled unless the user has the Create or Admin role (`canManageMappings`).

### Connectors

| Step | Code |
|---|---|
| Export | `ConnectorGridComponent.exportConfigurations` writes `connectors.json`. Each item is cut down to `identifier`, `connectorType`, `enabled`, `name`, `properties` by `prepareConnectorConfigurationForApi`. The configurations in the grid come from `GET /configuration/connector/instance`, which masks sensitive properties. |
| Import dialog | [`ImportConnectorsComponent`](../../dynamic-mapper-ui/src/shared/connector-configuration/import/import-connectors.component.ts) |
| Per connector | Drops HTTP connectors, sets a new identifier, sets `enabled = false`, and posts with `createConfiguration(config, true)`. It checks `response.ok` itself because the fetch client resolves on 4xx and 5xx. |
| Masked secrets | Detected by comparing property values with `****`. A warning tells the user to re-enter them. |
| Service side | `ConfigurationController.createConnectorConfiguration` (Admin) rejects `HTTP`, drops properties that do not apply, and saves. It drops a masked (`****`) sensitive property on create, because a new configuration has no stored secret to restore. |

The import button is disabled unless the user has the Admin role.

### Service configuration

| Step | Code |
|---|---|
| Export | `ServiceConfigurationComponent.clickedExportServiceConfiguration` reads `GET /configuration/service` and writes `service-configuration.json`. It reads the service, not the form, so settings the form does not show are included. |
| Import dialog | [`ImportServiceConfigurationComponent`](../../dynamic-mapper-ui/src/configuration/import/import-service-configuration.component.ts): parse, show counts, confirm, then apply. |
| Apply | `PUT /configuration/service` with the whole parsed object. |
| Service side | `ConfigurationController.updateServiceConfiguration` (Admin) reads the body into the existing configuration with Jackson's `readerForUpdating`. Absent keys leave the stored value unchanged, and most settings also ignore a `null` (`@JsonSetter(nulls = SKIP)`); `codeTemplates` has no `@JsonMerge`, so a present map replaces the stored map. Then it persists, toggles the notification clients if outbound mapping changed, and caches the new configuration. |

### Differences at a glance

| | Mappings | Connectors | Service configuration |
|---|---|---|---|
| File shape | array | array | object |
| Identity on import | new identifier, always a new item | new identifier, always a new item | single document |
| Effect on existing data | none, additive | none, additive | overwrites keys present in the file |
| Active state | forced inactive | forced disabled | not applicable |
| Secrets | none | masked as `****`, must be re-entered | none |
| Confirmation step | none, applies on drop | none, applies on drop | explicit "Replace settings" |
| Role | Create or Admin | Admin | Admin |

### Traps

- **The service configuration import skips the code template checks.** The `PUT /configuration/code/{id}`
  rules (no change to read-only templates, cache refresh for `SHARED` and `SYSTEM`) are not applied. The imported
  `SYSTEM` template is overwritten again at the next start, but the imported `SHARED` code is not loaded into the
  running engine until something rebuilds it.
- **Imported masked secrets are dropped, not stored.** The create endpoint calls `restoreMaskedSecrets`
  with no original, which removes every sensitive property still set to `****`. The connector then has
  no value for it until the user enters one.
- **Mapping import is not atomic and has no duplicate check.** A failure halfway leaves the first part
  created.
- **Mapping files do not carry shared code.** A Smart Function that calls helpers defined in the `SHARED` template
  fails on a tenant that lacks them.
