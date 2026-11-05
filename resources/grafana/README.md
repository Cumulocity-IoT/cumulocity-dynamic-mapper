# Grafana Dashboards

Two dashboards for the Dynamic Mapper service's Micrometer metrics (exported via OTLP to
Grafana Alloy; `otel.instrumentation.micrometer.enabled=true`, see `application-prod.properties`):

- **`dynamic-mapper-dashboard.json`** — per-tenant/per-connector drill-down: message rates,
  processing time, errors, cache hit rates, connector status.
- **`dynamic-mapper-tenants-overview-dashboard.json`** — fleet-wide overview, no selectors: which
  tenants this service is currently deployed to, how many connectors each has and their status,
  mapping counts, and which build version(s) are running. Meant to answer "what's out there"
  before drilling into the first dashboard for a specific tenant/connector.

## Import

Grafana → Dashboards → New → Import → upload the dashboard JSON file, then select a
Prometheus-compatible datasource when prompted.

## `dynamic-mapper-dashboard.json` — Variables

- **datasource** — any Prometheus-compatible datasource (Prometheus/Mimir/VictoriaMetrics).
- **tenant** — from the `tenant` label on `dynmapper_inbound_message_total`.
- **connectorName** — from the `connectorName` label, scoped to the selected tenant(s).
- **connectorType** — from the `connectorType` label (e.g. `MQTT`, `KAFKA`, `HTTP`), scoped to the
  selected tenant(s) and connector name(s).

Connector metrics are tagged by `connectorName`/`connectorType` rather than the connector's
internal identifier, so dashboards and alerts read as e.g. `"Plant A MQTT" / MQTT` instead of an
opaque id. Two names could in principle collide (e.g. after a rename), in which case their series
sum together — this trades a theoretical collision for a dashboard that's actually readable.

## Known caveats — verify before relying on this dashboard

- **No `/actuator/prometheus` endpoint exists** in this service (no Spring Boot
  Actuator starter dependency found in source). Metrics reach your Prometheus-compatible
  store via OTLP → Alloy. **Confirmed in this environment**: Timer histograms
  (*Processing Time*, *C8Y Processing Time*) use the `_seconds_sum`/`_seconds_count`/
  `_seconds_bucket` suffixes, and process-level metrics (*CPU Utilization*, *Memory/Heap/
  Metaspace Usage*) use Micrometer's standard `process_cpu_usage`/`system_cpu_usage`/
  `jvm_memory_used_bytes`/`jvm_memory_max_bytes` names — these must come from Micrometer's
  core binders via some auto-config path, since no explicit Actuator dependency was found.
  If you point this dashboard at a different Alloy/Mimir pipeline or deployment, re-verify
  these names in Explore before trusting the panels; a different collector config can
  translate the same underlying metric to a different suffix.
- The **Processing Time** Timers only publish `_bucket` series (needed for the Max/p100
  panels) since `.publishPercentileHistogram()` was added to them — this requires a
  redeploy, and only produces data for traffic processed after that redeploy.
- **Outbound identity cache hit rate** can't be shown as a percentage: the code
  only counts outbound hits (`dynmapper_outbound_identity_cache_hits_total`),
  there's no outbound "total lookups" counter to divide by. Only a hits/sec rate
  is shown for outbound; the inbound panel has a true hit-rate percentage since
  `dynmapper_inbound_identity_requests_total` counts every lookup.
- **C8Y Processing Time** and **Available C8Y Connections** are process-wide,
  untagged metrics — the Tenant/Connector Name/Connector Type selectors don't filter them.
- The Inventory cache (`dynmapper_inbound_inventory_cache_size`) has no hit
  counter at all, so only its size is shown.

## `dynamic-mapper-tenants-overview-dashboard.json`

### Variables

- **datasource** — any Prometheus-compatible datasource. No tenant/connector selectors — this
  dashboard is deliberately fleet-wide; use `dynamic-mapper-dashboard.json` to drill into one
  tenant/connector.

### `isolation: MULTI_TENANT` — why every per-tenant query uses `max by (tenant, ...)`

This service's `cumulocity.json` sets `"isolation": "MULTI_TENANT"`: every replica/pod serves
every subscribed tenant — tenants are never pinned to one instance. That means a per-tenant
gauge (cache size, connector status) is reported **independently by every replica**, each
carrying its own Prometheus/OTLP `instance` label. A naive `dynmapper_connector_status` query
therefore returns one series per `(tenant, connector, instance)`, not per `(tenant, connector)`
— joined into a table, that surfaces as extra, seemingly-duplicated columns instead of one
clean row per tenant. Every query on this dashboard wraps the raw metric in
`max by (tenant, ...)` (or `count by (tenant, ...)` on top of that) to collapse the replica
dimension away before it reaches a table or join.

The same reasoning means **`dynmapper_build_info` ("Deployed Version(s)") has no `tenant`
label and is fleet-wide, not per-tenant** — every tenant shares the same running instances, so
"which version is this tenant on" isn't a meaningful question under this isolation mode. If
more than one version tile shows, a rollout is in progress and it affects all tenants equally.

### Why the Tenant Overview table's query looks the way it does

The consolidated table (panel "Tenants") needs nine different per-tenant numbers side by side
(connector count, FAILED count, three cache sizes, two message rates, two mapping totals), one
column each, one row per tenant — a pivot keyed by `tenant`. Two approaches were tried and
discarded before the current one:

1. **One query per metric (`labelsToFields` + `joinByField` on `tenant`), with
   `legendFormat: "__auto"` on every target.** Without a distinctive legend, every target's
   value field ends up named identically, so the join can't tell them apart and falls back to
   generic suffixes (`Value`, `Value 1`, `Value 2`, ...) that don't correspond to any particular
   query in a way `renameByName` can target reliably.
2. **One query, `label_replace`-tagging each sub-expression with a `metric` label, pivoted via
   `labelsToFields`'s `valueLabel` option.** `valueLabel` pivots *across time*, not across an
   arbitrary key like `tenant` — with every sub-query being an instant query (all sharing the
   same timestamp) and `tenant` not involved in the pivot key, every tenant's row for a given
   metric collapsed onto the one shared timestamp, discarding everything but the last processed
   tenant.

**Current approach**: nine separate queries joined on `tenant` (the structurally correct tool for
"many same-keyed values become many columns"), each with an explicit, literal `legendFormat`
(e.g. `"Connectors Configured"`) instead of `"__auto"`, so the join has something distinct to
key each output column on. In practice this correctly names most columns, but Grafana's join
still leaves a handful of un-renamed `Value`/`Value N`/`Value #X` fields alongside the correctly
named ones (the exact collision-disambiguation Grafana falls back to for the remainder isn't
something this dashboard relies on knowing). Rather than chase the exact convention, the last
transformation step is `filterFieldsByName` with `exclude: {"pattern": "^Value( .*|#.*)?$"}` —
a convention-agnostic cleanup that hides **any** field still generically named `Value`-something
after the rename pass, regardless of its exact suffix. A correctly renamed column (e.g.
"Connectors Configured") never matches that pattern, so this only ever removes genuine leftovers.

### Rows

- **Fleet Overview** — Active Tenants / Connectors Configured / Connectors FAILED and Deployed
  Version(s) (all fleet-wide).
- **Tenant Overview** — one consolidated table, one row per tenant: connector count, FAILED
  count, the three cache sizes, inbound/outbound msgs/sec, and inbound/outbound mapping totals.
  Derived from `dynmapper_inbound_inventory_cache_size` et al., which are created unconditionally
  the moment a tenant subscribes — before any connector is configured or message flows — and
  removed on unsubscribe (`BootstrapService.cleanTenantResources` sweeps the registry for that
  tenant's meters). This makes them the most reliable "is this tenant currently here" signal
  available, independent of whether the tenant is actively doing anything.
- **Connectors** — one row per `(tenant, connector)` with its live status; see
  `dynmapper_connector_status` below for the ordinal mapping.
- **Mappings** — one row per `(tenant, direction, transformation type)` registered for a
  subscribed tenant, zero counts shown explicitly rather than hidden (so the full set of
  direction × transformation-type combinations is always visible, not just the ones currently in
  use). `dynmapper_mapping_count` is exposed by `MappingCacheManager` for every direction
  (`INBOUND`/`OUTBOUND`) × transformation type combination, registered the moment a tenant
  subscribes; each gauge's value function re-reads the live mapping cache on every scrape, so
  counts are always current and no mutation path (add/remove/update/reload) needs to touch a
  counter, and the query is deduplicated across replicas the same way as the other per-tenant
  gauges (see `isolation: MULTI_TENANT` above). Legacy/deprecated transformation types
  (`DEFAULT`, `SUBSTITUTION_AS_CODE`) are included for completeness — expect them to taper to
  zero as mappings migrate to `JSONATA`/`SMART_FUNCTION`.
- **Activity by Tenant** — horizontal bar gauges of inbound/outbound msgs/sec per tenant; the
  same numbers as the table's two rate columns, shown as a quick visual comparison across
  tenants.

`dynmapper_build_info`, added specifically to answer "what version is running", is the standard
Prometheus "info" pattern: a gauge always `1`, with the real value in the `version` tag, since
Micrometer has no native way to publish a plain string. **Needs a redeploy before it shows
anything.**

## Source metrics

| Metric | Type | Tags | Source |
|---|---|---|---|
| `dynmapper_inbound_message_total` | Counter | tenant, connectorName, connectorType | `InboundMessageDispatcher` |
| `dynmapper_inbound_processing_time` | Timer | tenant, connectorName, connectorType | `InboundMessageDispatcher` |
| `dynmapper_inbound_processing_errors_total` | Counter | tenant, connectorName, connectorType | `InboundMessageDispatcher` |
| `dynmapper_outbound_message_total` | Counter | tenant, connectorName, connectorType | `OutboundMessageDispatcher` |
| `dynmapper_outbound_processing_time` | Timer | tenant, connectorName, connectorType | `OutboundMessageDispatcher` |
| `dynmapper_outbound_processing_errors_total` | Counter | tenant, connectorName, connectorType | `OutboundMessageDispatcher` |
| `dynmapper_c8y_request_processing_time` | Timer | — | `C8YAgent` |
| `dynmapper_available_c8y_connections` | Gauge | — | `C8YAgent` |
| `dynmapper_inbound_identity_requests_total` | Counter | tenant | `C8YAgent` |
| `dynmapper_inbound_identity_cache_hits_total` | Counter | tenant | `C8YAgent` |
| `dynmapper_outbound_identity_cache_hits_total` | Counter | tenant | `C8YAgent` |
| `dynmapper_inbound_identity_cache_size` | Gauge | tenant | `InboundExternalIdCache` |
| `dynmapper_outbound_identity_cache_size` | Gauge | tenant | `OutboundExternalIdCache` |
| `dynmapper_inbound_inventory_cache_size` | Gauge | tenant | `InventoryCache` |
| `dynmapper_connector_status` | Gauge | tenant, connectorName, connectorType | `ConnectionStateManager` |
| `dynmapper_build_info` | Gauge (always 1) | version | `App` |
| `dynmapper_mapping_count` | Gauge | tenant, direction, transformationType | `MappingCacheManager` |

`dynmapper_inbound_processing_errors_total` / `dynmapper_outbound_processing_errors_total` and
`dynmapper_connector_status` were added to close the gap in detecting errors: previously a failed
message or a dead connector was only visible in logs or the REST status endpoint, with nothing
Grafana could alert on.

- The two error counters increment once per message when at least one of its processing contexts
  ends in an error, when mapping resolution itself throws, or when the pipeline throws a hard
  exception — not once per individual error within a message.
- `dynmapper_connector_status` reports the `ConnectorStatus` enum's **ordinal** (declaration order:
  `UNKNOWN=0, CONFIGURED=1, ENABLED=2, CONNECTING=3, CONNECTED=4, DISCONNECTED=5, DISCONNECTING=6,
  FAILED=7, RETRYING=8`), since Micrometer gauges are numeric. The dashboard's "Connector Status
  Over Time" panel maps these back to names/colors via Grafana value mappings. Treat the ordinal
  itself as an opaque code, not a severity scale (e.g. 7 > 4 does not mean "worse").
