# MQTT Load Tests

Python scripts that publish synthetic telemetry at a target aggregate rate
against the Cumulocity MQTT Service (or a public broker, for the older
scripts), to load-test Dynamic Mapper inbound mappings end to end.

| Script | Purpose | Payload |
|--------|---------|---------|
| [loadTest_01.py](loadTest_01.py) | Simplest case: one device, fixed message count | Single temperature reading |
| [loadTest_02.py](loadTest_02.py) | Batched array messages across geolocation/statistics event types | Arrays of events (JSONata-friendly) |
| [loadTest_03.py](loadTest_03.py) | Sustained geolocation event stream | Single geolocation event per message |
| [loadTest_04.py](loadTest_04.py) | Current default — telemetry/error mix, graceful shutdown, live counters | Smart Function payload |
| [loadTest_05.py](loadTest_05.py) | **Not MQTT** — ingests directly via the Cumulocity HTTP API (measurements/events), for comparing outbound-path throughput without a broker in between | HTTP `POST` |

All four MQTT scripts (01–04) share the same CLI, defined in
[mqtt_load_common.py](mqtt_load_common.py); `loadTest_05.py` is HTTP-only and
does not use it.

## Setup

```bash
pip install -r ../requirements.txt
eval $(c8y sessions login)      # or otherwise export C8Y_TENANT / C8Y_USERNAME / C8Y_PASSWORD
```

Cert auth (the default, see below) additionally requires:
- `openssl` on `PATH`
- an active `c8y` CLI session whose user has the **Mqtt service** permission
  to manage trusted certificates
- `C8Y_TENANT` exported (used as the MQTT username field)

## Running

```bash
# Defaults (each script has its own default TOTAL_TPS — see --help)
python3 loadTest_04.py

# Target 250 msg/s aggregate, spread across as many connections as the
# broker's per-client rate cap requires
python3 loadTest_04.py --total-tps 250

# Full option reference
python3 loadTest_04.py --help
```

Every option can also be set via an environment variable (useful for CI or
shell wrappers) — flags take precedence. Key ones:

| Flag | Env var | Default | Meaning |
|------|---------|---------|---------|
| `--total-tps` | `TOTAL_TPS` | script-specific | Target aggregate publish rate (msg/s) across all worker connections |
| `--max-tps-per-client` | `MAX_TPS_PER_CLIENT` | `90` | Per-connection rate cap; the Cumulocity MQTT Service enforces ~100 msg/s per client, so this stays a bit under it |
| `--auth` | `MQTT_AUTH` | `cert` | `cert` (X.509 client certs, see below) or `password` (username/password or a C8Y JWT) |
| `--broker` | `MQTT_BROKER` / `C8Y_DOMAIN` | — | MQTT broker host |
| `--port` | `MQTT_PORT` | `9883` | MQTT broker port |
| `--cert-days` | `DM_CERT_DAYS` | `2` | Validity of generated client certificates |

`WORKERS` (the number of MQTT connections) and the per-connection TPS are
always derived automatically: `WORKERS = ceil(total_tps / max_tps_per_client)`.

## Authentication

### Cert auth (default, `--auth cert`)

Mirrors [`resources/testing/integration/create-mqtt-service-x509-cert.sh`](../../integration/create-mqtt-service-x509-cert.sh):
a self-signed X.509 client certificate is generated per worker connection and
uploaded as a *trusted certificate* on the tenant (`c8y devicemanagement
certificates create --autoRegistrationEnabled`), which the MQTT Service
accepts as its own trust anchor. The cert's CN **is** the MQTT `clientId`,
and the tenant id goes in the MQTT username field — no password is sent.

One cert is provisioned **per worker connection**, not one for the whole
run: the MQTT Service ties a clientId to its cert, so a single cert can only
back one connection at a time. Certs are uploaded at startup and deleted
again (best-effort) on exit, whether that's a clean finish or Ctrl-C/SIGTERM.

This avoids the main failure mode of the old JWT-based auth: a
`C8Y_HEADER_AUTHORIZATION` bearer token captured once and reused across a
long-running load test will eventually expire mid-run and every worker
starts failing with "Not authorized". Certs are valid for `--cert-days` days
regardless of how long the test runs.

### Password auth (`--auth password`)

Falls back to the previous behavior for compatibility: `MQTT_USERNAME`/
`MQTT_PASSWORD`, or `C8Y_TENANT`+`C8Y_USERNAME` plus a raw JWT from
`C8Y_HEADER_AUTHORIZATION` (the `Bearer ` prefix is stripped automatically).
No certificates are provisioned in this mode.

## Notes

- `loadTest_01.py`–`loadTest_03.py` are older/experimental variants kept for
  reference against different payload shapes; `loadTest_04.py` is the
  actively maintained one and the best starting point for new runs.
- `loadTest_05.py` bypasses MQTT entirely (direct HTTP ingestion) and is
  unaffected by the auth mode discussion above — it always uses
  `C8Y_HEADER_AUTHORIZATION` or `C8Y_TENANT`/`C8Y_USERNAME`/`C8Y_PASSWORD`
  Basic auth.
- Stop any script with Ctrl-C for a graceful shutdown: final publish/fail
  counters are printed and, in cert-auth mode, provisioned certificates are
  deleted from the tenant.
