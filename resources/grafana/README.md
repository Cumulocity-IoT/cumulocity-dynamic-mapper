# Grafana Dashboard

`dynamic-mapper-dashboard.json` — an observability dashboard for the Dynamic Mapper
service's Micrometer metrics (exported via OTLP to Grafana Alloy;
`otel.instrumentation.micrometer.enabled=true`, see `application-prod.properties`).

## Import

Grafana → Dashboards → New → Import → upload `dynamic-mapper-dashboard.json`,
then select a Prometheus-compatible datasource when prompted.

## Variables

- **datasource** — any Prometheus-compatible datasource (Prometheus/Mimir/VictoriaMetrics).
- **tenant** — from the `tenant` label on `dynmapper_inbound_message_total`.
- **connector** — from the `connector` label, scoped to the selected tenant(s).

## Known caveats — verify before relying on this dashboard

- **No `/actuator/prometheus` endpoint exists** in this service (no Spring Boot
  Actuator / `micrometer-registry-prometheus` dependency). Metrics reach your
  Prometheus-compatible store via OTLP → Alloy. The exact names your collector
  produces for Timer histograms (*Processing Time*, *C8Y Processing Time* panels)
  depend on its unit-suffix handling — this dashboard assumes `_sum`/`_count`/`_bucket`
  with **no** unit infix. If your pipeline adds one, you'll see
  `..._milliseconds_sum` / `..._seconds_sum` etc. instead — check via Explore's
  metrics browser against `dynmapper_inbound_processing_time` and adjust the
  affected panel queries.
- **CPU Utilization** is not an application metric — no such metric name exists in
  this codebase. The panel is a best-effort query against
  `process_runtime_jvm_cpu_utilization`, the conventional metric name from the
  OTel Java runtime-telemetry auto-instrumentation this service enables
  (`otel.instrumentation.runtime-telemetry.enabled=true`). Confirm the name in
  your metrics browser.
- **Outbound identity cache hit rate** can't be shown as a percentage: the code
  only counts outbound hits (`dynmapper_outbound_identity_cache_hits_total`),
  there's no outbound "total lookups" counter to divide by. Only a hits/sec rate
  is shown for outbound; the inbound panel has a true hit-rate percentage since
  `dynmapper_inbound_identity_requests_total` counts every lookup.
- **C8Y Processing Time** and **Available C8Y Connections** are process-wide,
  untagged metrics — the Tenant/Connector selectors don't filter them.
- The Inventory cache (`dynmapper_inbound_inventory_cache_size`) has no hit
  counter at all, so only its size is shown.

## Source metrics

| Metric | Type | Tags | Source |
|---|---|---|---|
| `dynmapper_inbound_message_total` | Counter | tenant, connector | `InboundMessageDispatcher` |
| `dynmapper_inbound_processing_time` | Timer | tenant, connector | `InboundMessageDispatcher` |
| `dynmapper_inbound_processing_errors_total` | Counter | tenant, connector | `InboundMessageDispatcher` |
| `dynmapper_outbound_message_total` | Counter | tenant, connector | `OutboundMessageDispatcher` |
| `dynmapper_outbound_processing_time` | Timer | tenant, connector | `OutboundMessageDispatcher` |
| `dynmapper_outbound_processing_errors_total` | Counter | tenant, connector | `OutboundMessageDispatcher` |
| `dynmapper_c8y_request_processing_time` | Timer | — | `C8YAgent` |
| `dynmapper_available_c8y_connections` | Gauge | — | `C8YAgent` |
| `dynmapper_inbound_identity_requests_total` | Counter | tenant | `C8YAgent` |
| `dynmapper_inbound_identity_cache_hits_total` | Counter | tenant | `C8YAgent` |
| `dynmapper_outbound_identity_cache_hits_total` | Counter | tenant | `C8YAgent` |
| `dynmapper_inbound_identity_cache_size` | Gauge | tenant | `InboundExternalIdCache` |
| `dynmapper_outbound_identity_cache_size` | Gauge | tenant | `OutboundExternalIdCache` |
| `dynmapper_inbound_inventory_cache_size` | Gauge | tenant | `InventoryCache` |
| `dynmapper_connector_status` | Gauge | tenant, connector | `ConnectionStateManager` |

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
