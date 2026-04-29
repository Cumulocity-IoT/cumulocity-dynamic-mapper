# my-processor — Dynamic Mapper test microservice

Minimal Python/Flask microservice that demonstrates the Dynamic Mapper
**custom routing** feature (`cumulocityType: "custom"`).

The mapper calls this service at `/service/my-processor/ingest` and maps the
Smart Function `action` field to the HTTP method:

| Smart Function `action` | HTTP method | Behaviour |
|-------------------------|-------------|-----------|
| `create`                | POST        | Append a new reading for the device |
| `update`                | PUT         | Replace all readings for the device (full update) |
| `patch`                 | PATCH       | Merge supplied fields into the most recent reading |
| `delete`                | DELETE      | Remove all readings for the device |

## Matching Smart Function template

`dynamic-mapper-service/src/main/resources/templates/template-SMART-INBOUND-10.js`

Sample payload sent to this service (POST / PUT / PATCH):
```json
{
  "deviceId":  "sensor-berlin-01",
  "timestamp": "2026-04-29T12:00:00.000Z",
  "reading":   23.5
}
```

## Running locally

```bash
pip install -r requirements.txt
python app.py        # starts on port 80 (or SERVER_PORT env var)
```

Test all four actions locally (adjust port if needed):

```bash
# POST — create
curl -s -X POST http://localhost:80/ingest \
  -H 'Content-Type: application/json' \
  -d '{"deviceId":"dev-01","reading":22.1,"timestamp":"2026-04-29T10:00:00Z"}' | jq

# PUT — update (replace)
curl -s -X PUT http://localhost:80/ingest \
  -H 'Content-Type: application/json' \
  -d '{"deviceId":"dev-01","reading":25.0,"timestamp":"2026-04-29T11:00:00Z"}' | jq

# PATCH — partial update
curl -s -X PATCH http://localhost:80/ingest \
  -H 'Content-Type: application/json' \
  -d '{"deviceId":"dev-01","reading":26.3}' | jq

# DELETE — remove device readings
curl -s -X DELETE http://localhost:80/ingest \
  -H 'Content-Type: application/json' \
  -d '{"deviceId":"dev-01"}' | jq

# Inspect all stored readings
curl -s http://localhost:80/readings | jq

# Inspect readings for a specific device
curl -s http://localhost:80/readings/dev-01 | jq

# Clear the store
curl -s -X DELETE http://localhost:80/readings | jq
```

## Building and deploying to Cumulocity

```bash
cd resources/test-env/microservice

# Build Docker image + create ZIP (requires Docker)
./build.sh

# Build + upload to the connected Cumulocity tenant (requires go-c8y-cli)
./build.sh --push
```

The script produces `my-processor.zip` which can also be uploaded manually via
the Cumulocity Administration → Ecosystem → Microservices UI.

### Cross-platform builds (Apple Silicon)

```bash
BUILD_PLATFORM=linux/amd64 ./build.sh   # default; suitable for Cumulocity
BUILD_PLATFORM=linux/arm64 ./build.sh   # for ARM-based test environments
```

## Endpoints

| Method | Path                      | Description |
|--------|---------------------------|-------------|
| GET    | `/health`                 | Liveness / readiness probe |
| POST   | `/ingest`                 | Create new reading (called by mapper: action=create) |
| PUT    | `/ingest`                 | Replace readings (called by mapper: action=update) |
| PATCH  | `/ingest`                 | Partial update (called by mapper: action=patch) |
| DELETE | `/ingest`                 | Delete device readings (called by mapper: action=delete) |
| GET    | `/readings`               | List all stored readings |
| GET    | `/readings/<deviceId>`    | List readings for one device |
| DELETE | `/readings`               | Clear the entire store |
