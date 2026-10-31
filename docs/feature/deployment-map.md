# Deployment map

The deployment map says which mappings run on which connectors. A mapping that is not deployed to
a connector never runs there, however active it is. Two views exist: the **defined** deployment (what
an administrator asked for) and the **effective** deployment (what the connectors are running now).
"My mapping does nothing" is most often a mapping that is defined but not effective, or not defined
at all.

---

## Requirements

**What it is for.** Choosing, per mapping, the connectors it applies to, and showing whether that
choice is in force.

- **Deployment is explicit.** A new mapping is deployed nowhere. There is no "deploy to all
  connectors" default; a mapping with no entry runs on no connector.
- **A mapping runs on a connector only if all three conditions hold:** the mapping is active, the
  mapping is deployed to that connector, and the connector can handle the mapping's topic (a topic with
  `+` or `#` needs a connector that supports wildcards). Failing any one condition keeps the
  mapping off that connector without an error on the mapping itself.
- **One mapping can be deployed to several connectors, and one connector serves many mappings.**
  The relation is many to many.
- **Defined is intent, effective is fact.**
  - *Defined* is what was saved. It survives restarts and is the same for everyone.
  - *Effective* is derived from the running connectors. A mapping appears under a connector only
    after that connector has applied it. A disabled or disconnected connector contributes nothing
    to the effective view even when mappings are defined for it.
- **A change takes effect without a reconnect.** Saving a deployment subscribes and unsubscribes the
  mapping on the affected connectors immediately. If a connector is not connected yet, the change is
  applied as soon as it is.
- **Unknown connectors are rejected.** A deployment that names a connector that does not exist fails
  with a client error and changes nothing.
- **Saving replaces the list.** The list sent for a mapping is the complete new list; connectors left
  out lose the mapping.
- **Housekeeping keeps the map clean.** Deleting a mapping removes its entry. Deleting a connector
  removes it from every mapping. A connector that disappeared by other means is dropped from the
  map at the next start.
- **The map can be reset.** An administrator can clear the whole deployment map. Every mapping is then
  undeployed and stops running until it is deployed again. This is a configuration wipe, not a cache
  refresh.
- **Everyone with access can read both views.** Changing a deployment needs the Create or Admin role;
  resetting the whole map needs the Admin role (see [roles-and-permissions.md](roles-and-permissions.md)).

**Non-goals.** There is no deployment by pattern, tag, or direction. The deployment does not
move or copy mappings between tenants, and import/export does not carry it (see
[import-export.md](import-export.md)).

---

## Implementation

### Parts

| Part | Role |
|---|---|
| [`DeploymentController`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/controller/DeploymentController.java) | REST under `/deployment`. |
| [`DeploymentMapService`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/mapping/deployment/DeploymentMapService.java) | In-memory map per tenant, persisted to the inventory. |
| [`AConnectorClient`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/connector/core/client/AConnectorClient.java) | Applies the map: `isDeployedInConnector`, `reconcileSubscriptions`, `collectSubscribedMappingsAll`. |
| [`DeploymentMapEntry`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/model/DeploymentMapEntry.java) | Response shape of the effective view: mapping identifier plus the connector configurations. |

### Endpoints

| Method and path | Role | Returns |
|---|---|---|
| `GET /deployment/defined` | any | `{ mappingIdentifier: [connectorIdentifier] }` for the whole tenant |
| `GET /deployment/defined/{mappingIdentifier}` | any | the connector identifier list of one mapping, `[]` when absent |
| `PUT /deployment/defined/{mappingIdentifier}` | Admin or Create | `201`; body is a JSON array of connector identifiers |
| `GET /deployment/effective` | any | `{ mappingIdentifier: DeploymentMapEntry }` built from the live connectors |
| `POST /operation` with `RESET_DEPLOYMENT_MAP` | Admin | clears the map, reconciles every connector |

### Defined: storage

`DeploymentMapService` keeps `Map<tenant, Map<mappingIdentifier, List<connectorIdentifier>>>`.
The inner lists are `CopyOnWriteArrayList`, because connector threads read them while the API writes.
All mutations and the persist that follows run under one monitor per tenant, so persists reach the
inventory in the order of the in-memory changes. The map is stored as the `d11r_deploymentMap`
fragment of the service's own managed object (`MapperServiceRepresentation`). Persist failure rolls the
in-memory map back to what the managed object holds and throws, so the REST call does not report
success for a change that was lost.

Reads (`getDeploymentMap`, `getDeployedConnectors`) return copies and never insert entries. An earlier
`computeIfAbsent` on the read path created and persisted an empty entry for every mapping that was
merely evaluated.

### Applying a change

`PUT /defined/{id}`:

1. `validateConnectorsExist` compares the identifiers with the stored connector configurations and
   returns `400` with the unknown ones.
2. The previous list is united with the new one to get the **affected** connectors.
3. `updateDeploymentMapEntry` replaces the list (de-duplicated) and persists.
4. For each affected connector with a running client, `reconcileSubscriptions()` runs. A failure on one
   connector is logged and does not stop the others.

`reconcileSubscriptions()` rebuilds the inbound and outbound effective sets from the mapping caches,
keeping only mappings for which `isDeployedInConnector` is true. When the client is not connected and is
not a passive receiver (HTTP), it schedules one retry after a fixed delay instead of dropping the work.

The same reconcile runs for `RELOAD_MAPPINGS` and after `RESET_DEPLOYMENT_MAP`.

### Effective: how it is built

`GET /effective` loops over the tenant's registered connector clients. Each client adds the identifiers
in its `MappingSubscriptionManager` effective inbound and outbound sets, together with its own
cleaned configuration (secrets masked). The result is built on request and is not stored. The inbound
router uses the same effective set (`isMappingInboundDeployed`) when it filters resolved mappings, so
the effective view is what message processing actually checks.

### UI

[`MappingService`](../../dynamic-mapper-ui/src/mapping/core/mapping.service.ts) merges mappings with the
effective map (`loadMappingsEnriched`, private) so the grid shows the connectors each mapping runs on. The
defined list is read when a mapping is opened for editing and written by
[`MappingStepperService`](../../dynamic-mapper-ui/src/mapping/service/mapping-stepper.service.ts) when the
mapping is saved. The grid
reloads when the service's managed object reports a changed `d11r_deploymentMap` fragment.

### Traps

- **Defined and effective can disagree on purpose.** Do not "fix" a mapping that lists a connector in
  the defined view and is missing from the effective view without checking the connector: it may be
  disabled, disconnected, or reject the topic's wildcards (a warning is logged once per evaluation).
- **The effective view needs `Content-Type: application/json` on GET.** `/effective`, `/defined`, and
  `/defined/{id}` declare `consumes = application/json`, so a GET without that header is answered
  with `415`. The UI always sends it; scripts must too.
- **`PUT` does not check that the mapping exists.** The OpenAPI text promises `404` for an unknown
  mapping, the code never returns it, and a stray entry is stored. Deleting a mapping removes only its
  own entry.
- **"Reset deployment cache" in the grid is `RESET_DEPLOYMENT_MAP`.** It initializes the map with
  `reset = true` and persists the empty map. The UI follows it with `RELOAD_MAPPINGS`.
- **Router fallback.** `InboundMessageRouter.isMappingDeployed` returns `true` when the check throws.
  A broken registry lookup therefore widens, not narrows, what runs.
- **Deployment is not part of a mapping version or export.** Restoring or importing a mapping leaves
  it undeployed.
