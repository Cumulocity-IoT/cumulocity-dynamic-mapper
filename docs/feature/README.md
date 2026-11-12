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
| [inbound-flow-diagram.md](inbound-flow-diagram.md) | Supporting diagram: the inbound processor chain end to end |
| [mapping-processing-outbound.md](mapping-processing-outbound.md) | Cumulocity → broker pipeline |
| [mapping-validation.md](mapping-validation.md) | The rules a mapping is checked against |
| [mapping-versioning.md](mapping-versioning.md) | Drafts, publishing, rollback, retention |
| [substitution-editor.md](substitution-editor.md) | The Transformation step's substitution list and editor, in a split view |
| [mapping-testing.md](mapping-testing.md) | Dry Run and Live Test of a mapping, test devices |
| [mapping-testing-isolation.md](mapping-testing-isolation.md) | Keeping tests apart from live state: caches, mapping status, Smart Function state, real devices |
| [identity-resolution.md](identity-resolution.md) | External ID ↔ managed object, implicit device creation |
| [deployment-map.md](deployment-map.md) | Which mappings run on which connector: defined vs. effective deployment, live reconcile, reset |
| [mapping-tree.md](mapping-tree.md) | The topic tree that resolves inbound topics to mappings, and the Hierarchy mapping page |
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
| [connector-mqtt.md](connector-mqtt.md) · [connector-mqtt-service.md](connector-mqtt-service.md) · [connector-kafka.md](connector-kafka.md) · [connector-pulsar.md](connector-pulsar.md) · [connector-amqp.md](connector-amqp.md) · [connector-http.md](connector-http.md) · [connector-http-polling.md](connector-http-polling.md) · [connector-webhook.md](connector-webhook.md) · [connector-google-pubsub.md](connector-google-pubsub.md) | Per-connector specifics |

### Tooling

| Page | Covers |
|---|---|
| [message-explorer.md](message-explorer.md) | Live inspection of broker traffic, seeding a mapping from a captured message |
| [ai-agent-generation.md](ai-agent-generation.md) | Generating substitutions and Smart Functions via the AI Agent Manager |
| [service-logs.md](service-logs.md) | Microservice logs for sub-tenants of a multi-tenant deployment: collection, per-tenant events, the Service logs page |
| [import-export.md](import-export.md) | Import and export of mappings, connectors, and the service configuration, and how the three differ |

### Outbound delivery and configuration

| Page | Covers |
|---|---|
| [outbound-subscriptions.md](outbound-subscriptions.md) | How a device gets subscribed for outbound mappings: static, by type, by group, and the resync backfill |
| [device-isolation.md](device-isolation.md) | Restricting outbound MQTT Service delivery to registered clients, the device↔client map, the relation endpoints |
| [code-templates.md](code-templates.md) | System, shared, and per-type code templates behind Smart Functions, and the reset |
| [roles-and-permissions.md](roles-and-permissions.md) | The three roles, the operation permission table, feature flags, what the UI hides or disables |

---

## Not documented yet

No feature is currently listed as missing a page. Add a row here, with where it lives and why it matters,
when a feature ships without one.
