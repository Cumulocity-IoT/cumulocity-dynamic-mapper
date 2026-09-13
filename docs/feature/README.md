# Feature documentation

One page per feature. Each page is split into two parts, and the split is the point:

| Part | Answers | Changes when |
|---|---|---|
| **Requirements** | What must be true for this feature, observable from outside — the contract, the rules, the guarantees, the deliberate non-goals. No class names, no file paths. | The product decision changes |
| **Implementation** | How it is built — classes, flow, data structures, the reasoning behind non-obvious choices, and the traps. Links into the source. | The code changes |

Why: the requirements outlive any given implementation and are what you check a change
against; the implementation half is what you read before touching the code, and it goes stale
by design. Mixing them makes it impossible to tell "this is how it works" from "this is how it
has to work" — which is exactly the question you have when deciding whether a change is allowed.

**Writing the requirements half.** State rules as assertions about behaviour a user or an
integrator could observe. "A mapping asking for a QoS the connector cannot honour runs at the
strongest level the connector supports" is a requirement. "`Qos.clampTo` returns …" is not —
that belongs below. Include the deliberate non-goals; a reader needs to know that something was
decided against rather than forgotten.

---

## Pages

### Mappings

| Page | Covers |
|---|---|
| [mapping-processing-inbound.md](mapping-processing-inbound.md) | Broker → Cumulocity pipeline |
| [mapping-processing-outbound.md](mapping-processing-outbound.md) | Cumulocity → broker pipeline |
| [mapping-validation.md](mapping-validation.md) | The rules a mapping is checked against |
| [mapping-versioning.md](mapping-versioning.md) | Drafts, publishing, rollback, retention |
| [mapping-testing.md](mapping-testing.md) | Testing a mapping without a device |
| [identity-resolution.md](identity-resolution.md) | External ID ↔ managed object, implicit device creation |
| [reliability.md](reliability.md) | QoS, processing timeouts, failure handling, status counters |

### Transformations

| Page | Covers |
|---|---|
| [transformation-smart-functions.md](transformation-smart-functions.md) | JavaScript transformations on GraalVM |
| [transformation-jsonata.md](transformation-jsonata.md) | JSONata expressions |
| [transformation-java-extensions.md](transformation-java-extensions.md) | Java processor extensions |

### Connectors

| Page | Covers |
|---|---|
| [connector-framework.md](connector-framework.md) | The shared abstraction every connector extends |
| [connector-mqtt.md](connector-mqtt.md) · [connector-mqtt-service.md](connector-mqtt-service.md) · [connector-kafka.md](connector-kafka.md) · [connector-pulsar.md](connector-pulsar.md) · [connector-amqp.md](connector-amqp.md) · [connector-http.md](connector-http.md) · [connector-webhook.md](connector-webhook.md) · [connector-google-pubsub.md](connector-google-pubsub.md) | Per-connector specifics |

---

## Not documented yet

Features that exist in the product and have no page. Listed so the gap is visible rather than
rediscovered; ordered by how much a reader loses without them.

| Feature | Where it lives | Why it matters |
|---|---|---|
| **Message Explorer** | `ExplorerController` (`/session`, `/session/{id}/messages`), `ExplorerListenerRegistry`, per-connector `subscribeExplorer` | Live inspection of broker traffic and building a mapping from a real payload. Replaced snooping in 6.4.0, and snooping's removal *is* documented — its successor is not. Has per-connector subtleties (Kafka needs an isolated consumer group, MQTT Service has no real subscriptions) that are currently only visible in code comments. |
| **Deployment map** | `DeploymentController` (`/defined`, `/effective`), `DeploymentMapService` | Which mappings run on which connector — a core concept with no page at all. "Defined" vs. "effective" deployment is not obvious, and mis-deploying is a common cause of "my mapping does nothing". |
| **Outbound subscription management** | `NotificationSubscriptionController` (`/subscription`, `/type`, `/type/resync/{type}`, `/group`, `/device`) | How a device comes to be subscribed at all: static, by device type, by group, plus the resync that backfills pre-existing devices. `mapping-processing-outbound.md` documents the pipeline *after* a notification arrives, never how the subscription got there. |
| **Code templates** | `ConfigurationController` (`/code`, `/code/{id}`), `codeTemplates` in `ServiceConfiguration` | The shared/system JavaScript library and the per-type starting templates behind every Smart Function. Customer-editable, and part of what a tenant loses on a configuration reset. |
| **Device isolation / client relations** | `ClientRelationController`, `deviceIsolationMQTTServiceEnabled` | Restricting outbound MQTT Service delivery to registered clients, and the device↔client mapping behind it. A tenant-wide switch whose semantics are undocumented. |
| **AI agent integration** | `AIAgentService`, `jsonataAgent` / `smartFunctionAgent` settings | Generating substitutions and Smart Functions from a payload. Named as a headline capability in the project overview, with no page describing what it does or requires. |
| **Import / export** | UI only — mappings, connectors (#563), service configuration (#567) | Three different scopes with three different behaviours (mappings additive, connectors imported disabled because secrets are masked, service configuration a full overwrite). The differences are the documentation-worthy part. |
| **Mapping tree** | `MappingController` `/tree`, `MappingTreeNode` | The topic hierarchy view, and how the resolver tree it renders relates to mapping resolution. |
| **Roles and permissions** | `ROLE_DYNAMIC_MAPPER_ADMIN` / `_CREATE`, `Feature` flags | Which role may do what, and what the UI hides rather than disables. Renamed in 5.5.0 (see `CHANGES.md`) with no feature page to point at. |
