# Webhook Test Environment

Test the dynamic-mapper-service's **Webhook connector** (outbound only) against
a free, hosted [RequestBin](https://pipedream.com/requestbin) endpoint —
no local server or Docker container needed, since RequestBin is a public URL
that Cumulocity Cloud can reach directly.

## Why RequestBin

The Webhook connector only supports **outbound** mappings (Cumulocity →
HTTP): it has no `subscribe`/inbound support at all — publishing a
measurement/event/alarm/operation triggers an HTTP request to a configured
`baseUrl`. RequestBin gives you a throwaway public URL that captures and
displays every HTTP request sent to it, which makes it an ideal, zero-setup
inspection target for testing outbound webhook mappings without standing up
your own receiver.

## 1. Create a RequestBin

1. Open https://pipedream.com/requestbin
2. Click **Create Request Bin** (no account required for a temporary bin;
   sign in with a free Pipedream account if you want the bin to persist).
3. Copy the generated URL, e.g. `https://<bin-id>.m.pipedream.net`.
4. Keep the RequestBin inspector tab open — every request the mapper sends
   will show up there in real time.

## 2. Create the Webhook connector

Unlike a local broker, RequestBin is publicly reachable, so the connector can
be created **enabled** right away — no local-service workaround needed.

Configure it in the UI (Connectors → Add connector → Webhook), or via the
REST API:

```bash
export C8Y_BASEURL="https://<your-tenant>.cumulocity.com"
export C8Y_TENANT="<tenant-id>"
export C8Y_USER="<user>"
export C8Y_PASSWORD="<password>"
export REQUESTBIN_URL="https://<bin-id>.m.pipedream.net"

curl -s -X POST "${C8Y_BASEURL}/service/dynamic-mapper-service/configuration/connector/instance" \
  -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}" \
  -H 'Content-Type: application/json' \
  -d "{
    \"identifier\": \"test-webhook-connector\",
    \"connectorType\": \"WEB_HOOK\",
    \"name\": \"Test Webhook Connector\",
    \"description\": \"RequestBin test target\",
    \"enabled\": true,
    \"properties\": {
      \"cumulocityInternal\": false,
      \"baseUrl\": \"${REQUESTBIN_URL}\",
      \"authentication\": \"\",
      \"headerAccept\": \"application/json\",
      \"headers\": {}
    }
  }"
```

Or with go-c8y-cli:

```bash
c8y api --method POST --url /service/dynamic-mapper-service/configuration/connector/instance \
  --header 'Content-Type: application/json' \
  --data "{
    \"identifier\": \"test-webhook-connector\",
    \"connectorType\": \"WEB_HOOK\",
    \"name\": \"Test Webhook Connector\",
    \"enabled\": true,
    \"properties\": { \"baseUrl\": \"${REQUESTBIN_URL}\" }
  }"
```

### Connector properties reference

| Property | Required | Notes |
|----------|----------|-------|
| `baseUrl` | yes | Target base URL — the RequestBin URL |
| `authentication` | no | `Basic`, `Bearer`, or omit/empty for no auth (RequestBin needs none) |
| `user` / `password` | no | Only used when `authentication: "Basic"` |
| `token` | no | Only used when `authentication: "Bearer"` |
| `headerAccept` | no | Default `application/json` |
| `baseUrlHealthEndpoint` | no | Optional GET health-check endpoint |
| `headers` | no | Map of additional static headers to send on every request |
| `cumulocityInternal` | no | Leave `false` — `true` is for the internal Cumulocity-hosted variant |

Connect the connector (only needed if you created it with `enabled: false`,
or after a disconnect):

```bash
curl -s -X POST "${C8Y_BASEURL}/service/dynamic-mapper-service/operation" \
  -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}" \
  -H 'Content-Type: application/json' \
  -d '{"operation": "CONNECT", "parameter": {"connectorIdentifier": "test-webhook-connector"}}'
```

Check status:

```bash
curl -s "${C8Y_BASEURL}/service/dynamic-mapper-service/monitoring/status/connector/test-webhook-connector" \
  -u "${C8Y_TENANT}/${C8Y_USER}:${C8Y_PASSWORD}"
```

## 3. Create an outbound mapping

In the UI: **Mapping → Outbound → Add mapping**, select connector
`test-webhook-connector`, and configure something like:

- **Filter**: `$exists(c8y_TemperatureMeasurement)` (or any device/fragment
  filter matching test data you plan to send)
- **Publish topic**: e.g. `measurement` — this is appended to `baseUrl`, so
  the final request goes to `${REQUESTBIN_URL}/measurement`
- **Template / substitutions**: map source fields to the outbound JSON body
  as with any other outbound mapping (JSONata or Smart Function)

Deploy the mapping to `test-webhook-connector`.

## 4. Trigger and inspect

Create a measurement (or event/alarm, depending on your mapping's API
filter) on a device subscribed to this mapping, e.g.:

```bash
c8y measurements create --device "<deviceId>" \
  --type c8y_TemperatureMeasurement \
  --data 'c8y_TemperatureMeasurement.T.value=23.5,c8y_TemperatureMeasurement.T.unit=C'
```

Switch to the RequestBin inspector tab — the HTTP request (method, headers,
body) that the Webhook connector sent should appear within a few seconds.
Use it to verify the request path, headers (including `Authorization` if
`Basic`/`Bearer` auth was configured), and JSON body produced by the mapping.

## Troubleshooting

### No request arrives in RequestBin

- Check connector status is `CONNECTED`
  (`GET /monitoring/status/connector/test-webhook-connector`).
- Check the mapping's filter actually matches the payload you sent, and that
  the mapping is deployed to `test-webhook-connector`.
- Check the microservice logs for HTTP errors (e.g. DNS/TLS failures,
  non-2xx response from RequestBin).

### Wrong path / 404 in RequestBin

The full request URL is `baseUrl` + the mapping's resolved publish topic
(`buildFullPath` in `WebHook.java`). If the path looks wrong, check the
mapping's **Publish topic** field rather than the connector's `baseUrl`.

### Bin data disappeared

Anonymous (non-logged-in) RequestBins on Pipedream are temporary and expire
after a period of inactivity. Sign in with a free account for a persistent
bin if you need it to survive longer test sessions.

## Security Note

⚠️ RequestBin URLs are public — anyone with the URL can see every request
sent to it. Never send real credentials, tokens, or production data through
a RequestBin; use synthetic test payloads only.
