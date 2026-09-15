# Dynamic Mapper Service for Cumulocity

## Release 6.5.2

### Persisted data and REST payloads are unchanged

Mappings, connector configurations, the service configuration and the mapping-status fragment
(`d11r_mapping`) all keep their existing field names and types. Everything below is either
additive on the wire or a behaviour change in the service.

### `maxFailureCount` now actually deactivates a mapping (behaviour change)

`maxFailureCount` has always been documented — and described in the mapping editor — as "the
mapping is automatically deactivated when this is exceeded", but no code ever set the mapping
inactive; only a `MAPPING_FAILURE_EVENT` was raised. It now performs the deactivation.

**If you already have mappings with `maxFailureCount > 0`, they can now be deactivated where
previously nothing happened.** The counter is a *consecutive* failure streak — it is reset by the
first message that processes without an error — so a mapping with an occasional transient failure
is not affected. `maxFailureCount = 0` (the default) disables the check entirely.

### Mapping QoS is now enforced per connector (behaviour change)

Each connector declares the QoS levels it can honour, and a mapping asking for more is clamped to
the strongest level the connector actually implements (logged once per level). Previously the
declaration existed but was never applied outside of MQTT subscriptions. Notable effects:

- AMQP 0.9.1 / AMQP 1.0: a mapping set to `EXACTLY_ONCE` used to be published **non**-persistently
  (a stronger request produced a weaker guarantee); it is now clamped to `AT_LEAST_ONCE` and
  published persistently.
- Cumulocity MQTT Service: outbound publishing honours the mapping's QoS instead of always using
  `AT_LEAST_ONCE`. A mapping set to `AT_MOST_ONCE` is now sent fire-and-forget.
- `ConnectorSpecification` gained an optional `supportedQos` field (additive).

### Processing timeouts are bounded and consistent (behaviour change)

- A pipeline with no per-message budget (any non-Smart-Function mapping) previously waited
  **indefinitely** for its result; it is now bounded by a 120 s ceiling, after which the message
  is cancelled and — for QoS > 0 — redelivered.
- `pipelineTimeoutMS` must be greater than `maxCPUTimeMS`; a configuration that violates this is
  now raised at runtime instead of making the CPU budget unreachable. The service-configuration
  form rejects such a pair.
- Per-call-site fallbacks for these settings (5 000 / 8 000 / 30 000 ms for the same value) were
  unified on the configured value.

### Breaking API change for Java processor extensions

`MappingStatus.UNSPECIFIED_MAPPING_STATUS` has been **removed** from the
`dynamic-mapper-interface` artifact. It was a single mutable `static` shared by every subscribed
tenant, so each tenant reported the sum of all tenants' unmatched-message counters. Use
`MappingStatus.createUnspecified()` (a per-tenant instance) or `MappingStatus.isUnspecified()`
instead.

An extension compiled against an earlier interface jar that referenced this field fails with
`NoSuchFieldError` and must be recompiled. Extensions that do not reference it — which is the
expected case, since it is internal status bookkeeping — are unaffected.

`MappingStatus.equals`/`hashCode` now compare the mapping `identifier` rather than `id`, and
`reset()` additionally clears `currentFailureCount`.

### Export/import for the service configuration

Adds the counterpart of the connector export/import from 6.4.x, as a **snapshot restore**: the
whole service configuration can be written to a JSON file and put back later — the case being the
reset that replaces the document with a fresh default and loses the settings, the AI agent names
and any custom Smart Function `codeTemplates` along with them.

- *Export* writes `GET /configuration/service` to `service-configuration.json`, code templates
  included, since a snapshot that omitted them would not restore the tenant.
- *Import* replaces the current settings after an explicit confirmation showing what the file
  holds. There is no merge or per-field selection — this is a restore, not a promotion tool.
- Unlike the connector import, no credentials are involved, so a restored configuration is
  immediately complete. The settings take effect at once: enabling outbound mappings reconnects
  the connectors, which the confirmation states.

### Service configuration reorganised

The settings were split across General / AI Agent / Caching / Logging, which put unrelated things
together: *General* mixed feature switches with GraalVM engine internals, *Logging* mixed log
verbosity with the events published to Cumulocity, and *Caching* held retention policies that are
not caches.

- New **Processing** tab for the Smart Function execution limits and the GraalVM engine settings;
  the *Rotate GraalVM Engine* action moved with them.
- **Logging** is now **Monitoring**, split into "Events published to Cumulocity" and
  "Log verbosity". The old `/serviceConfiguration/logging` path still resolves.
- `mappingVersionRetention` moved from Caching to General; *Caching* now holds the caches plus
  flow-state retention, grouped by what they cache.
- New **Expert settings** toggle in the action bar hides the seven settings that require knowing
  the runtime internals (GraalVM engine sizing, alias-map caching, per-substitution logging).
  Off by default, remembered per browser; each tab states how many settings it is hiding.

### Multi-tenancy fixes

- The catch-all mapping status was a single mutable `static` shared by every subscribed tenant,
  so each tenant reported the sum of all tenants' unmatched-message counters (see the API note
  above).
- The external-ID resolution cache, its reverse index and its per-ID locks were **never** cleared
  on unsubscribe. They grew for the lifetime of the process, and a tenant that unsubscribed and
  re-subscribed resolved external IDs to managed objects that may have been deleted meanwhile.
- The cached `RestConnector` holding a tenant's service-user credentials was never dropped on
  unsubscribe, and `executeWithProcessingMode(mode, tenant, …)` could cache a connector built
  from one tenant's context under another tenant's key. It now refuses to cache a mismatch.
- `ConnectorRegistry` left an empty entry per unsubscribed tenant.

### Mapping status reporting

- Messages arriving on a topic that no mapping covers are now counted on the catch-all status
  instead of only being logged.
- That row is labelled **"Unmapped messages"** (previously "Unspecified") and is sorted to the end
  of the statistics list. Its identifier on the wire is unchanged (`UNSPECIFIED`).

## Release 6.4.0

### Removal of the Snooping feature (breaking change)

The **snooping** feature has been removed. Snooping previously allowed a mapping to passively
record sample messages from the broker so the recorded payloads could be copied into the source
template. This capability is superseded by the **Message Explorer**, which provides a dedicated,
more powerful interface to inspect incoming messages and build mappings from real payloads.

**Breaking API change:** the following service operations have been **removed** and will return an
error if invoked:

- `SNOOP_MAPPING`
- `SNOOP_RESET`
- `COPY_SNOOPED_SOURCE_TEMPLATE`

The `snoopStatus` / `snoopedTemplates` fields on mappings and the `snoopedTemplatesActive` /
`snoopedTemplatesTotal` fields on mapping status are no longer produced. Mappings persisted by
earlier versions that still contain these fields continue to load — the now-unknown properties are
ignored on deserialization and disappear the next time the mapping is saved.

## Release 5.5.0

In this release 5.5.0 of the Cumulocity Dynamic Mapper, there is a breaking change concerning the naming of roles and the enforcement of permissions for features.

### Name of the roles
The following roles have been renamed:

**Previous roles:**
```
    ROLE_DYNAMIC_MAPPING_ADMIN
    ROLE_DYNAMIC_MAPPING_CREATE
    ROLE_DYNAMIC_MAPPING_HTTP_CONNECTOR_CREATE
```

**New roles:**

```
    ROLE_DYNAMIC_MAPPER_ADMIN
    ROLE_DYNAMIC_MAPPER_CREATE
    ROLE_DYNAMIC_MAPPER_HTTP_CONNECTOR_CREATE
```

### Permission Enforcement
Permissions are now strictly enforced, whereas they were not enforced in previous versions.

**Default permissions:**
- Users without any assigned roles will have **read-only access** to:
  - Mappings
  - Service configuration
  - Connectors

**Enhanced permissions:**
- To create, modify, or delete resources, users must be granted the appropriate roles listed above
- Administrative functions require the `ROLE_DYNAMIC_MAPPER_ADMIN` role
- Creating new mappings requires the `ROLE_DYNAMIC_MAPPER_CREATE` role
- Creating HTTP connectors requires the `ROLE_DYNAMIC__HTTP_CONNECTOR_CREATE` role

To be able to use more feature additional roles have to be granted:
     <div class="table-responsive table-width-80">
      <table class="table _table-striped">
        <thead class="thead-light">
          <tr>
            <th style="width: 40%;">Dynamic Mapper Feature</th>
            <th class="text-center" style="width: 20%;">No role</th>
            <th class="text-center" style="width: 20%;">Create</th>
            <th class="text-center" style="width: 20%;">Admin</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td><strong>Mapping Read</strong></td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr class="table-light">
            <td><strong>Mapping Create/Edit</strong></td>
            <td class="text-center text-muted">-</td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr>
            <td><strong>Mapping Delete</strong></td>
            <td class="text-center text-muted">-</td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr class="table-light">
            <td><strong>Mapping Activate/Deactivate</strong></td>
            <td class="text-center text-muted">-</td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr>
            <td><strong>Mapping Snoop/Debug/Filter</strong></td>
            <td class="text-center text-muted">-</td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr class="table-light">
            <td><strong>Connector Read</strong></td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr>
            <td><strong>Connector Create/Edit</strong></td>
            <td class="text-center text-muted">-</td>
            <td class="text-center text-muted">-</td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr class="table-light">
            <td><strong>Connector Delete</strong></td>
            <td class="text-center text-muted">-</td>
            <td class="text-center text-muted">-</td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr>
            <td><strong>Connector Activate/Deactivate</strong></td>
            <td class="text-center text-muted">-</td>
            <td class="text-center text-muted">-</td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr class="table-light">
            <td><strong>Service Configuration Read</strong></td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
          <tr>
            <td><strong>Service Configuration Edit</strong></td>
            <td class="text-center text-muted">-</td>
            <td class="text-center text-muted">-</td>
            <td class="text-center"><strong>X</strong></td>
          </tr>
        </tbody>
      </table>
    </div>
    
### Migration Notes
- Update any existing role assignments to use the new role names
- Review user permissions and assign appropriate roles to maintain existing functionality
- Users who previously had implicit access to create/modify features will need explicit role assignments
