# Roles and permissions

Who may read, change, and operate what in the Dynamic Mapper: the three roles, what each one allows in
the service, the feature flags the UI reads, and how the UI shows a missing permission. The roles were
renamed in 5.5.0 (see `CHANGES.md`).

---

## Requirements

**What it is for.** Letting many people look at mappings while few can change them, and fewer can change
connectors, configuration, or the running service.

### Roles

| Role | Allows |
|---|---|
| `ROLE_DYNAMIC_MAPPER_ADMIN` | Everything below, plus connectors, service configuration, code templates, extensions, service logs, and maintenance operations. |
| `ROLE_DYNAMIC_MAPPER_CREATE` | Mappings and what they need: create, edit, delete, drafts, versions, activation, deployment, outbound subscriptions, client relations, Message Explorer, mapping tests. |
| `ROLE_DYNAMIC_MAPPER_HTTP_CONNECTOR_CREATE` | Only sending data to the Default HTTP Connector's ingest endpoint. |

- **Admin includes Create.** Wherever Create is enough, Admin is enough.
- **The HTTP connector role is separate.** Admin does not include it, and it includes nothing else. It is for
  devices and gateways that post data.
- **Reading is open to every user of the application,** without a mapper role: mappings, connectors (secrets
  masked), the service configuration, code templates, status, the mapping tree, deployment, subscriptions,
  relations. The exceptions are mapping drafts and versions, which need Create, and the service log, which
  needs Admin.
- **Secrets are never returned.** Connector responses mask sensitive properties.
- **Operations are checked per operation.** A user may run a maintenance operation only if its listed role is
  held, and a new operation cannot ship without a decision on its role.
- **The UI mirrors the roles but is not the guard.** The service refuses a call without the role even if the UI
  let it through. The UI exists to avoid offering what will fail.
- **Outbound features also depend on the service configuration.** With outbound mapping off, outbound
  subscription and relation calls are refused for everyone, and the UI removes the outbound pages.

### What needs which role

| Area | Read | Change |
|---|---|---|
| Mappings, drafts, versions, tests | read mappings: any; drafts and versions: Create | Create |
| Deployment of a mapping | any | Create |
| Outbound subscriptions | any | Create |
| Client relations | any | Create |
| Message Explorer | Create | Create |
| Connectors | any | Admin |
| Service configuration, code templates | any | Admin |
| Extensions | Admin or Create (list, read) | Admin |
| Service logs | Admin | Admin (flush) |
| Default HTTP Connector ingest | not applicable | HTTP connector role |

### Operations (`POST /operation`)

| Role | Operations |
|---|---|
| Create | `ACTIVATE_MAPPING`, `ADD_SAMPLE_MAPPINGS`, `APPLY_MAPPING_FILTER`, `DEBUG_MAPPING`, `REFRESH_STATUS_MAPPING`, `RELOAD_MAPPINGS`, `UPDATE_CODE` |
| Admin | `CLEAR_CACHE`, `CLEAR_CACHE_DEVICE_TO_CLIENT`, `CONNECT`, `DISCONNECT`, `INIT_CODE_TEMPLATES`, `REFRESH_NOTIFICATIONS_SUBSCRIPTIONS`, `RELOAD_EXTENSIONS`, `RESET_DEPLOYMENT_MAP`, `RESET_STATISTICS_MAPPING`, `ROTATE_GRAALVM_ENGINE` |

### Hide or disable

- **Pages that make no sense for the tenant are hidden:** outbound mappings and outbound subscriptions when
  outbound mapping is off; the client relation page when device isolation is off.
- **Pages that need Admin are guarded:** the Service logs page does not open without Admin.
- **Actions the user may not perform are shown disabled,** with a hint where there is room for one (for
  example "Requires the Mapping Admin or Mapping Create role"), so a user can see that the feature exists.
- **Read-only users get read-only editors.** A mapping opens for viewing; Save, activation, import, and
  deployment controls are disabled.

**Non-goals.** No per-mapping or per-connector permissions, no separate roles for subscriptions or code
templates, and no approval step.

---

## Implementation

### Role sources

The roles are declared in [`cumulocity.json`](../../dynamic-mapper-service/src/main/configuration/cumulocity.json)
under `roles`. The service's own user holds none of them: they are in `roles`, not `requiredRoles`. An endpoint that the
service calls itself, such as the webhook echo used by outbound webhook mappings, therefore cannot require a
mapper role and is open (`POST /webhook/echo/**`, a side-effect-free echo).

`ROLE_DYNAMIC_MAPPING_*` (MAPPING, not MAPPER) in `CHANGES.md` is the pre-5.5.0 naming and is history, not a typo.

### Checks in code

| Mechanism | Where | Notes |
|---|---|---|
| `@PreAuthorize("hasRole('ROLE_DYNAMIC_MAPPER_ADMIN')")` | configuration, extension delete | Admin only. |
| `@PreAuthorize("hasAnyRole('…ADMIN', '…CREATE')")` | mapping, deployment, subscription, relation, explorer, test | Written inline or as the `ADMIN_CREATE_ROLES` constant. |
| `@PreAuthorize("hasRole('…HTTP_CONNECTOR_CREATE')")` | HTTP connector ingest | The controller also answers 403 itself. |
| [`Utils.userHasMappingAdminRole()` / `userHasMappingCreateRole()`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/controller/Utils.java) | service logs, `REQUIRED_ROLES`, `GET /configuration/feature` | `Create` returns true for Admin as well. |
| `REQUIRED_ROLES` table in [`OperationController`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/controller/OperationController.java) | `POST /operation` | `requireRoleFor` runs once before the switch. A static initializer throws at class load if an `Operation` has no entry. |

`@PreAuthorize` is not exercised by unit tests that instantiate controllers directly. Only the
`REQUIRED_ROLES` check is unit-testable.

**Auditing a controller.** A search for `@GetMapping`, `@PostMapping`, and the other shortcut annotations misses
endpoints written as `@RequestMapping(value = …, method = RequestMethod.X)`. `POST /test/mapping` was open for that
reason until it was found. Match both forms, and remember that the guard may be a per-controller constant.

### Feature flags

`GET /configuration/feature` returns [`Feature`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/Feature.java),
built from the service configuration, the deployment, and the calling user's authorities.

| Flag | Source |
|---|---|
| `outputMappingEnabled` | `outboundMappingEnabled` in the service configuration |
| `externalExtensionsEnabled` | the `APP.externalExtensionsEnabled` property |
| `userHasMappingCreateRole` | Create or Admin |
| `userHasMappingAdminRole` | Admin |
| `pulsarAvailable` | whether the tenant can use Pulsar |
| `deviceIsolationMQTTServiceEnabled` | the switch in [device-isolation.md](device-isolation.md) |
| `suppressDeprecationWarning`, `acceptedDeprecationNotice` | deprecation notice state |

The UI reads the flags once per route through `featureResolver` and `SharedService.getFeatures()`.

### What the UI does with them

| Mechanism | Examples |
|---|---|
| Remove navigation node | `navigation.factory.ts` drops the outbound mapping and outbound subscription nodes when `outputMappingEnabled` is false, and the client relation node when `deviceIsolationMQTTServiceEnabled` is false. |
| Route guard | [`AdminGuard`](../../dynamic-mapper-ui/src/shared/misc/admin.guard.ts) on the Service logs route. |
| Disable control | Service configuration page (all inputs and Save), code template actions, extension upload, connector import, mapping save, activation, import, subscription drawers, relation edits. |
| Hide control | Extension grid actions for non-admins; delete actions on the relation page when the user has neither role. |
| Read-only editor | `mapping-edit.resolver` and the stepper set editors read-only without Create. |

### Traps

- **The nav check hides pages but protects nothing.** Routes other than Service logs are not guarded in the UI,
  and many read endpoints have no role check; the guard that counts is the one in the service.
- **A flag the UI reads can disagree with the service.** `Feature` is computed per call; a role change takes
  effect for the UI only after the page reloads its features.
- **A control can be enabled for a user whose call will fail.** The mapping grid enables *Reset deployment cache*
  for Create and Admin, but the operation behind it (`RESET_DEPLOYMENT_MAP`) needs Admin, so a Create user gets
  `403` (`canManageMappings` in `mapping.component.ts`).
- **Roles are tenant roles.** The platform decides who holds them. The service does not keep its own user
  list.
