# REST Polling Test Environment

Minimal Python/Flask microservice that acts as the **poll target** for the
Dynamic Mapper's **REST Polling connector** (`connectorType: "REST_POLLING"`,
inbound only, see `HttpPollingConnector.java`). It serves a synthetic,
changing reading on every request, and optionally requires Basic or Bearer
auth so the connector's `authentication` config can be exercised.

**Bearer auth only works when running `app.py` locally** (see the warning in
step 2) — once deployed as a Cumulocity microservice, Cumulocity's own
platform gateway sits in front of every `/service/http-polling-mock/...`
request and requires the `Authorization` header to already be a valid
Cumulocity credential, before this app's own auth check ever runs. There is
only one `Authorization` header, so it can't carry both a real Cumulocity
credential (to pass the gateway) and an arbitrary `Bearer <token>` (to
satisfy this app). Basic auth works against the deployed microservice,
*if* `AUTH_USER`/`AUTH_PASSWORD` are set to a real Cumulocity tenant user's
credentials rather than made-up ones — see step 2.

## Why deploy this as a Cumulocity microservice (not a local Docker container)

Unlike the Kafka/AMQP/Pulsar test environments, this target does not need to
be reachable *by Cumulocity Cloud* — only by the dynamic-mapper-service
process itself, whether it runs locally or is deployed. Deploying the mock as
its own Cumulocity microservice (the same pattern as `../microservice/`)
means it is reachable at a stable base path —
`${C8Y_BASEURL}/service/http-polling-mock` — from the mapper **regardless of
where the mapper runs**, with no RequestBin-style public URL or "create
disabled, run locally" workaround needed (unlike `../webhook/` and
`../kafka/`).

## 1. Running locally (quick smoke test, no deploy)

```bash
cd resources/testing/environments/http-polling
pip install -r requirements.txt
python app.py        # starts on port 80 (or SERVER_PORT env var)
```

```bash
curl -s http://localhost:80/measurements | jq
# {"deviceId":"poll-sensor-01","timestamp":"...","temperature":23.47}

curl -s http://localhost:80/requests | jq       # inspect what was received
curl -s -X DELETE http://localhost:80/requests  # clear the log
```

Enable auth locally to test the connector's `authentication` handling. This
is where `AUTH_MODE=bearer` is actually testable end-to-end — running
locally, there is no Cumulocity gateway in front of `app.py`, so its own
`Authorization` check is the only one that applies:

```bash
AUTH_MODE=basic AUTH_USER=poller AUTH_PASSWORD=secret python app.py
# or
AUTH_MODE=bearer AUTH_TOKEN=test-token python app.py
```

An unauthenticated/incorrectly-authenticated request now gets `401`:

```bash
curl -s -i http://localhost:80/measurements                        # 401
curl -s -u poller:secret http://localhost:80/measurements | jq     # 200 (basic)
curl -s -H 'Authorization: Bearer test-token' http://localhost:80/measurements | jq  # 200 (bearer)
```

To exercise the `REST_POLLING` connector itself against this local instance,
point its `url` (a base URL — see step 3) at wherever the local process is
reachable from the dynamic-mapper-service (e.g. `http://localhost:80` if the
service also runs locally on the same host, with a mapping topic of
`measurements` to reach `/measurements`) — the same "run locally, create
the connector disabled, then enable" pattern the Kafka test environment
uses, since Cumulocity Cloud cannot reach a bare `localhost` port. See
`../kafka/README.md` for that workflow in full.

## 2. Building and deploying to Cumulocity

```bash
cd resources/testing/environments/http-polling

# Build Docker image + create ZIP (requires Docker)
./build.sh

# Build + upload to the connected Cumulocity tenant (requires go-c8y-cli)
./build.sh --push
```

The script produces `http-polling-mock.zip`, which can also be uploaded
manually via Cumulocity Administration → Ecosystem → Microservices.

To enable auth on the deployed instance, set environment variables on the
microservice (Administration → Ecosystem → Microservices →
`http-polling-mock` → Variables): `AUTH_MODE` (`none`/`basic`), `AUTH_USER`,
`AUTH_PASSWORD`.

⚠️ **`AUTH_MODE=bearer` does not work once deployed.** Cumulocity's platform
gateway requires the `Authorization` header on every
`/service/http-polling-mock/...` request to already be a valid Cumulocity
credential before the request reaches this app at all — an arbitrary
`Bearer <token>` gets rejected by the gateway itself. Use `AUTH_MODE=basic`
with **real Cumulocity tenant credentials** as `AUTH_USER`/`AUTH_PASSWORD`
against the deployed microservice (the same credential then satisfies both
the gateway and this app's own check); reserve `AUTH_MODE=bearer` for
running `app.py` locally (step 1), where there is no gateway in the way.

### Cross-platform builds (Apple Silicon)

```bash
BUILD_PLATFORM=linux/amd64 ./build.sh   # default; suitable for Cumulocity
BUILD_PLATFORM=linux/arm64 ./build.sh   # for ARM-based test environments
```

## 3. Create the REST Polling connector

```bash
export C8Y_BASEURL="https://<your-tenant>.cumulocity.com"
export C8Y_TENANT="<tenant-id>"
export C8Y_USER="<user>"
export C8Y_PASSWORD="<password>"

curl -s -X POST "${C8Y_BASEURL}/service/dynamic-mapper-service/configuration/connector/instance" \
  -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}" \
  -H 'Content-Type: application/json' \
  -d "{
    \"identifier\": \"test-rest-polling-connector\",
    \"connectorType\": \"REST_POLLING\",
    \"name\": \"Test REST Polling Connector\",
    \"description\": \"http-polling-mock test target\",
    \"enabled\": true,
    \"properties\": {
      \"url\": \"${C8Y_BASEURL}/service/http-polling-mock\",
      \"pollIntervalSeconds\": 30,
      \"authentication\": \"None\",
      \"headers\": {}
    }
  }"
```

`url` is a **base URL** — the mapping's topic gets appended as the request path (step 4), so it
deliberately does *not* end in `/measurements` here. One connector instance can serve several
mappings against different paths under this same base, e.g. a topic of `measurements` resolves to
`GET ${C8Y_BASEURL}/service/http-polling-mock/measurements`.

Or with go-c8y-cli:

```bash
c8y api --method POST --url /service/dynamic-mapper-service/configuration/connector/instance \
  --header 'Content-Type: application/json' \
  --data "{
    \"identifier\": \"test-rest-polling-connector\",
    \"connectorType\": \"REST_POLLING\",
    \"name\": \"Test REST Polling Connector\",
    \"enabled\": true,
    \"properties\": { \"url\": \"${C8Y_BASEURL}/service/http-polling-mock\", \"pollIntervalSeconds\": 30 }
  }"
```

If the mock was deployed with `AUTH_MODE=basic`, add the matching connector
properties — `user`/`password` must be a **real Cumulocity tenant user's**
credentials (see the caution in step 2), not arbitrary ones:

```json
{
  "authentication": "Basic",
  "user": "<tenant>/<user>",
  "password": "<password>"
}
```

`authentication: "Bearer"` only makes sense when the connector's `url`
points at a **locally-running** `app.py` (step 1) — against the deployed
microservice it will always fail, rejected by Cumulocity's gateway before
reaching the mock's own check.

### Connector properties reference

| Property | Required | Notes |
|----------|----------|-------|
| `url` | yes | Base URL — each deployed mapping's topic is appended as the request path |
| `pollIntervalSeconds` | no | Default `60`; **hard minimum `30`** — `isConfigValid` rejects anything lower |
| `authentication` | no | `None` (default), `Basic`, or `Bearer` |
| `user` / `password` | only if `authentication: "Basic"` | |
| `token` | only if `authentication: "Bearer"` | |
| `headers` | no | Map of additional static headers sent with every poll request |

Connect the connector (only needed if created with `enabled: false`, or after
a disconnect):

```bash
curl -s -X POST "${C8Y_BASEURL}/service/dynamic-mapper-service/operation" \
  -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}" \
  -H 'Content-Type: application/json' \
  -d '{"operation": "CONNECT", "parameter": {"connectorIdentifier": "test-rest-polling-connector"}}'
```

Check status:

```bash
curl -s "${C8Y_BASEURL}/service/dynamic-mapper-service/monitoring/status/connector/test-rest-polling-connector" \
  -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}"
```

## 4. Create an inbound mapping

In the UI: **Mapping → Inbound → Add mapping**, select connector
`test-rest-polling-connector`, and set the mapping's **topic** to
`measurements`. Unlike a broker topic, this is appended directly to the
connector's `url` as the request path — so this mapping polls
`${C8Y_BASEURL}/service/http-polling-mock/measurements`, matching the mock's
route. Map the response fields (`deviceId`, `timestamp`, `temperature`) to a
measurement, using the sample payload below as the source for the mapping
editor's test step.

Sample response body (what `/measurements` returns):
```json
{
  "deviceId": "poll-sensor-01",
  "timestamp": "2026-09-21T10:00:00.000Z",
  "temperature": 23.47
}
```

Deploy the mapping to `test-rest-polling-connector`.

## 5. Observe polling

Watch the mock's request log to confirm the connector is polling at the
configured interval, and that auth headers (if configured) are present:

```bash
watch -n 5 "curl -s ${C8Y_BASEURL}/service/http-polling-mock/requests | jq"
```

Each entry shows `receivedAt`, the request `headers` (`Authorization` is
redacted), and whether the request was `authorized`. Use `authorized` to verify
the configured credentials. Within ~30s (the enforced minimum interval) of connecting, new
entries should appear; the connector's `Message Explorer` / mapping test
results should show a new measurement each cycle with a different
`temperature`.

## 6. Example mappings (API-driven, three connector features at once)

`mappings/sample-mappings.json` is a single array-format file — the same shape the
Dynamic Mapper UI's mapping import/export uses (see `resources/samples/mappings-*.json`)
— containing three ready-to-use **Smart Function** mappings that together exercise the
connector features covered above end to end against this mock: two topics sharing one
connector instance, and the incremental-fetch cursor feature. Each mapping's `code` is a
base64-encoded JavaScript `onMessage(msg, context)` function (see `docs/smart-functions.md`)
rather than JSONata substitutions — required for mapping 3, whose response is a JSON array
and which builds one Cumulocity event per array item entirely in code. They complement
step 4 (which shows the equivalent one-mapping UI flow) rather than replace it.

| Mapping (`identifier`) | Topic → mock route | Demonstrates |
|---|---|---|
| `httppoll-meas` | `measurements` → `GET /measurements` | Baseline single-topic polling → `MEASUREMENT` |
| `httppoll-status` | `status` → `GET /status` | **Different topics, same connector**: deployed to the *same* connector instance as `httppoll-meas` — one poll job per topic, both sharing one `url`/credentials |
| `httppoll-events-cursor` | `events` → `GET /events?since=<cursor>` | **Incremental-fetch cursor**: a *separate* connector instance with `cursorParam`/`cursorExtractionExpression` configured (cursor/pagination settings are connector-level — see `docs/feature/connector-http-polling.md` — so this can't share the plain connector above); the code loops over the response array, building one Cumulocity event per new item |

### 6.1 Create the two connector instances

The plain connector (topics `measurements` + `status`, no cursor):

```bash
export C8Y_BASEURL="https://<your-tenant>.cumulocity.com"
export C8Y_TENANT="<tenant-id>"
export C8Y_USER="<user>"
export C8Y_PASSWORD="<password>"

curl -s -X POST "${C8Y_BASEURL}/service/dynamic-mapper-service/configuration/connector/instance" \
  -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}" \
  -H 'Content-Type: application/json' \
  -d "{
    \"identifier\": \"demo-rest-polling-connector\",
    \"connectorType\": \"REST_POLLING\",
    \"name\": \"Demo REST Polling Connector\",
    \"description\": \"http-polling-mock — measurements + status, no cursor\",
    \"enabled\": true,
    \"properties\": {
      \"url\": \"${C8Y_BASEURL}/service/http-polling-mock\",
      \"pollIntervalSeconds\": 30,
      \"authentication\": \"None\",
      \"headers\": {}
    }
  }"
```

The cursor-enabled connector (topic `events` only — `cursorParam`/`cursorExtractionExpression`
apply to every mapping on a connector, so the cursor demo needs its own instance):

```bash
curl -s -X POST "${C8Y_BASEURL}/service/dynamic-mapper-service/configuration/connector/instance" \
  -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}" \
  -H 'Content-Type: application/json' \
  -d "{
    \"identifier\": \"demo-rest-polling-connector-cursor\",
    \"connectorType\": \"REST_POLLING\",
    \"name\": \"Demo REST Polling Connector (cursor)\",
    \"description\": \"http-polling-mock — events, incremental-fetch cursor\",
    \"enabled\": true,
    \"properties\": {
      \"url\": \"${C8Y_BASEURL}/service/http-polling-mock\",
      \"pollIntervalSeconds\": 30,
      \"authentication\": \"None\",
      \"headers\": {},
      \"cursorParam\": \"since\",
      \"cursorExtractionExpression\": \"$max(id)\"
    }
  }"
```

Connect both (only needed if created with `enabled: false`, or after a disconnect):

```bash
for c in demo-rest-polling-connector demo-rest-polling-connector-cursor; do
  curl -s -X POST "${C8Y_BASEURL}/service/dynamic-mapper-service/operation" \
    -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}" \
    -H 'Content-Type: application/json' \
    -d "{\"operation\": \"CONNECT\", \"parameter\": {\"connectorIdentifier\": \"$c\"}}"
done
```

### 6.2 Create, deploy, and activate the three mappings

`mappings/sample-mappings.json` can be imported as-is via the UI (**Mapping → Inbound →
Import**), which deploys nothing by itself — deploy/activate each mapping afterwards as
usual. To do the whole thing via the REST API instead, each mapping is extracted from the
array by `identifier`, POSTed individually (`POST /mapping` takes one mapping object, not
an array), deployed to its connector (`PUT /deployment/defined/{identifier}` — the
mapping's own `identifier` field, e.g. `httppoll-meas`, not the numeric `id` the response
also carries), then activated (mappings are always created inactive, per the `POST
/mapping` contract):

```bash
create_deploy_activate() {
  local identifier="$1" connector="$2"

  python3 -c "
import json
mappings = json.load(open('mappings/sample-mappings.json'))
mapping = next(m for m in mappings if m['identifier'] == '$identifier')
json.dump(mapping, open('/tmp/${identifier}.json', 'w'))
"

  curl -s -X POST "${C8Y_BASEURL}/service/dynamic-mapper-service/mapping" \
    -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}" \
    -H 'Content-Type: application/json' \
    -d @"/tmp/${identifier}.json" > /dev/null

  curl -s -X PUT "${C8Y_BASEURL}/service/dynamic-mapper-service/deployment/defined/${identifier}" \
    -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}" \
    -H 'Content-Type: application/json' \
    --data "[\"${connector}\"]" > /dev/null

  curl -s -X POST "${C8Y_BASEURL}/service/dynamic-mapper-service/operation" \
    -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}" \
    -H 'Content-Type: application/json' \
    -d "{\"operation\": \"ACTIVATE_MAPPING\", \"parameter\": {\"id\": \"${identifier}\", \"active\": \"true\"}}"
}

create_deploy_activate httppoll-meas           demo-rest-polling-connector
create_deploy_activate httppoll-status          demo-rest-polling-connector
create_deploy_activate httppoll-events-cursor   demo-rest-polling-connector-cursor
```

`deployment/defined` takes a JSON array of connector identifiers — pass it as a literal
(`--data "[...]"` above), not via a shell variable substitution into a `--template`/`jq` filter;
see the "Gotchas" note in `docs/feature/connector-http-polling.md` about `PUT
/deployment/defined` silently mangling an array body under go-c8y-cli's `--template input.value`.

### 6.3 Verify

```bash
# All three poll jobs show up here once connected and deployed:
curl -s "${C8Y_BASEURL}/service/dynamic-mapper-service/monitoring/status/connector/demo-rest-polling-connector" \
  -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}"
curl -s "${C8Y_BASEURL}/service/dynamic-mapper-service/monitoring/status/connector/demo-rest-polling-connector-cursor" \
  -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}"

# The mock's request log should show /measurements, /status, and /events(?since=...) interleaved:
curl -s "${C8Y_BASEURL}/service/http-polling-mock/requests" | jq

# /events requests should show since= growing across cycles, not stuck at empty/absent —
# that's the cursor actually advancing.
curl -s "${C8Y_BASEURL}/service/http-polling-mock/requests" | jq '[.[] | select(.path == "/events")]'
```

## Troubleshooting

### No new entries in `/requests`

- Check connector status is `CONNECTED`
  (`GET /monitoring/status/connector/test-rest-polling-connector`) — if it's
  `RETRYING` or `FAILED`, the microservice logs will show the underlying HTTP
  error (DNS, TLS, non-2xx status).
- Confirm the mapping is deployed to `test-rest-polling-connector` — a
  connector with no mappings deployed to it has no poll jobs scheduled at
  all (`subscribe()` is only called per deployed mapping).

### 401 / connector stuck `RETRYING`, but nothing shows up in `/requests` at all

Against the **deployed** microservice, this means Cumulocity's own gateway
rejected the request (bad/missing Cumulocity credential in `Authorization`)
before it ever reached `app.py` — the mock's own auth check never even ran,
so there's nothing in `/requests` to show. Check the connector's
`authentication`/`user`/`password` are a real Cumulocity credential (see the
caution in step 2); `authentication: "Bearer"` against the deployed
microservice always fails this way.

### `authorized: false` in a `/requests` entry (local run)

Only possible when running `app.py` locally, since a gateway-rejected
request never reaches this log. Means the connector's
`authentication`/`user`/`password`/`token` properties don't match the mock's
own `AUTH_MODE`/`AUTH_USER`/`AUTH_PASSWORD`/`AUTH_TOKEN` env vars — check
both sides agree.

### `pollIntervalSeconds` rejected / connector never validates

Values below `30` fail `isConfigValid()` outright — the connector config
save will be rejected (or the connector simply never reaches `CONFIGURED`).
Use `30` or higher.

## Security Note

⚠️ This mock has no real authentication mechanism (fixed credentials read
from plain environment variables) — it exists purely to exercise the
connector's auth code paths in a test tenant. Never point it at real
credentials or deploy it to a production tenant.
