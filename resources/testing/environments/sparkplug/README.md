# Sparkplug B Test Environment

Local MQTT broker (HiveMQ CE) + the ThingsBoard [`tb-sparkplug-emulator`](https://github.com/thingsboard/sparkplug-emulator)
publishing simulated Sparkplug B NBIRTH/NDATA/DDATA/NDEATH traffic, for testing the
`SPARKPLUGB` mapping type without a real edge node.

## Why not just run the `docker run` command from the emulator's README?

```bash
docker run -d \
  --name tb-sparkplug-emulator \
  -e SPARKPLUG_SERVER_URL='tcp://192.168.1.100:1883' \
  -e SPARKPLUG_CLIENT_MQTT_USERNAME='YOUR_DEVICE_TOKEN' \
  -e SPARKPLUG_CLIENT_GROUP_ID='Plant_1' \
  -e SPARKPLUG_CLIENT_NODE_ID='EdgeNode_01' \
  -e SPARKPLUG_PUBLISH_INTERVAL='5000' \
  thingsboard/tb-sparkplug-emulator:latest
```

That command hard-codes `192.168.1.100` — your LAN IP for wherever the broker happens to be
running — and a `YOUR_DEVICE_TOKEN` username meant for ThingsBoard Cloud's per-device auth.
Two things bite people:

- **`localhost`/`127.0.0.1` does not work** as `SPARKPLUG_SERVER_URL` — inside the emulator's
  own container that resolves to the container itself, not your host or the broker.
- A LAN IP is fragile (changes with DHCP, doesn't work in CI, doesn't work if the broker is
  itself in a container on a different Docker network).

The [docker-compose.yaml](docker-compose.yaml) here puts both containers on the same Docker
network and lets the emulator address the broker by its **service name** (`hivemq`), which
Docker's embedded DNS resolves for you — no IP guessing needed. The bundled HiveMQ CE config
([hivemq-conf/config.xml](hivemq-conf/config.xml)) has no auth plugin enabled, so the
`SPARKPLUG_CLIENT_MQTT_USERNAME`/`_PASSWORD` device-token variables can simply be omitted.

## Quick Start

```bash
cd resources/testing/environments/sparkplug

# Start HiveMQ + the emulator
docker compose up -d

# Check both are healthy / running
docker compose ps

# Watch the emulator publish NBIRTH/NDATA
docker compose logs -f tb-sparkplug-emulator
```

Stop with:

```bash
docker compose down       # keep nothing running
docker compose down -v    # also drop the HiveMQ volume-less state (there is none by default)
```

## Access Points

- **MQTT (plain, no TLS, no auth)**: `tcp://localhost:1883` — this is what the Dynamic Mapper
  MQTT connector should point at when running on your host machine (Docker publishes the
  container's `1883` to the host's `1883`).
- **HiveMQ CE Control Center**: http://localhost:8080

If the Dynamic Mapper service itself also runs inside Docker, put it on the same
`sparkplug-network` (or the reverse: add its container to this compose file) and use the
`hivemq` service name instead of `localhost`.

## Verify Traffic With mosquitto_sub

```bash
mosquitto_sub -h localhost -p 1883 -t 'spBv1.0/#' -v
```

You should see the emulator's `NBIRTH` once at startup, followed by periodic `NDATA` every
`SPARKPLUG_PUBLISH_INTERVAL` ms (default here: 5000 ms / 5 s).

## Configuring the Dynamic Mapper Connector

- **Protocol**: `mqtt://`
- **Host**: `localhost` (or `hivemq` if Dynamic Mapper runs in the same Docker network)
- **Port**: `1883`
- **Username/Password**: not required (no auth plugin configured)

Create an MQTT connector pointing at the above (e.g. name it "Spark Simulator") and deploy
the mappings below against it. **"Is Sparkplug Host" should stay unchecked** — that's for
acting as a Sparkplug Primary Host Application (publishing your own `STATE` birth/death
certificates over `spBv1.0/STATE/<hostId>`), which this consuming/testing setup doesn't need.

## Mappings

[mappings/sample-mappings.json](mappings/sample-mappings.json) has two ready-to-import
`SPARKPLUGB` / `SMART_FUNCTION` inbound mappings that turn the emulator's protobuf traffic
into Cumulocity measurements, events and managed objects:

| Mapping | Topic | Handles |
|---|---|---|
| Sparkplug B - Edge Node | `spBv1.0/+/+/+` (4 segments) | `NBIRTH`, `NDATA`, `NDEATH` |
| Sparkplug B - Device | `spBv1.0/+/+/+/+` (5 segments) | `DBIRTH`, `DDATA`, `DDEATH` |

Two mappings, not one `spBv1.0/#`, on purpose:

- **Node-level and device-level messages need different externalId logic** — a Node's C8Y
  identity is `<groupId>_<edgeNodeId>`; a Device's is `<groupId>_<edgeNodeId>_<deviceId>`. One
  topic filter per level keeps that unambiguous instead of re-parsing the topic to decide which
  case you're in.
- **`spBv1.0/#` would also catch `STATE` messages** (`spBv1.0/STATE/<hostId>`, 3 segments) if
  Sparkplug Host mode is ever turned on on this connector — those aren't protobuf, and
  `SparkPlugBDeserializer` would fail to decode them. Exact-segment-count filters (`+/+/+` vs
  `+/+/+/+`) can't ever match a 3-segment topic, so this is structurally excluded rather than
  handled by extra code. See [mapping-processing-inbound.md](../../../../docs/feature/mapping-processing-inbound.md).

Each Smart Function (`onMessage`) is self-contained — no shared library dependency — and:

- Registers the Edge Node / Device as a Cumulocity `managedObject` on `NBIRTH`/`DBIRTH`
  (`cumulocityType: "managedObject"`, `externalSource` with `autoCreateDeviceMO: true`). This
  matters beyond visibility: `SendInboundProcessor.storeSparkPlugBBirthMessage` only persists
  the NBIRTH alias→metric map (needed to resolve later alias-only `NDATA` metrics) once the
  device exists and `context.getSourceId()` is set — which a `managedObject` create does.
- Maps numeric/boolean metrics from `NBIRTH`/`NDATA`/`DBIRTH`/`DDATA` into one `measurement`
  per message (fragment/series derived from the metric name, e.g. `Outputs/LEDs/Green` →
  fragment `Outputs_LEDs`, series `Green`).
- Maps string metrics (e.g. `MyNodeMetric05_String`) into individual `event`s.
- Turns `NDEATH`/`DDEATH` into a lifecycle `event`. The `sparkPlugB_isActive[_<deviceId>]`
  flag itself is maintained automatically by the backend
  (`SendInboundProcessor.updateSparkPlugBActiveStatus`) regardless of what the Smart Function
  returns — it doesn't need to be handled here.
- Ignores `NCMD`/`DCMD` (commands) — not sensor data to map.

Import via the Dynamic Mapper UI: **Mapping → Inbound → Import**, select
`mappings/sample-mappings.json`. Import deploys nothing by itself — after importing, open
each mapping, assign it to your connector, then **Activate**. See
[docs/feature/transformation-smart-functions.md](../../../../docs/feature/transformation-smart-functions.md)
and [docs/smart-functions.md](../../../../docs/smart-functions.md) for the Smart Function
API these build on.

### Expected result

Once both mappings are active and the emulator is publishing, you should see in Cumulocity:

- Two managed objects: `Sparkplug Edge Node EdgeNode_01` and one per emulated device
  (`Sparkplug Device Sparkplug Device 1`, `Sparkplug Device Sparkplug Device 2`).
- Periodic measurements of type `c8y_SparkplugMeasurement` on each, with fragments like
  `c8y_SparkplugMetrics.Current_Grid_Voltage` or `Outputs_LEDs.Green`.
- Events of type `c8y_SparkplugStringMetric` for string-valued metrics (e.g.
  `MyNodeMetric05_String`).

## Customizing the Emulator

Edit the `environment:` block in [docker-compose.yaml](docker-compose.yaml) and re-run
`docker compose up -d` to apply:

| Variable | Purpose | Default here |
|----------|---------|---------------|
| `SPARKPLUG_CLIENT_GROUP_ID` | Sparkplug Group ID (2nd topic segment) | `Plant_1` |
| `SPARKPLUG_CLIENT_NODE_ID` | Sparkplug Edge Node ID | `EdgeNode_01` |
| `SPARKPLUG_PUBLISH_INTERVAL` | Metrics publish interval, ms | `5000` |
| `SPARKPLUG_CLIENT_MQTT_CLIENT_ID` | MQTT client ID | emulator default |
| `SPARKPLUG_CLIENT_MQTT_USERNAME` / `_PASSWORD` | MQTT auth (unused against this broker) | unset |

To run multiple simulated edge nodes at once, duplicate the `tb-sparkplug-emulator` service
in the compose file under a new name with a different `SPARKPLUG_CLIENT_NODE_ID` (and
`container_name`).

## Troubleshooting

### `exec /usr/bin/java: exec format error` (Apple Silicon / arm64 Macs)

```
tb-sparkplug-emulator  | exec /usr/bin/java: exec format error
```

`thingsboard/tb-sparkplug-emulator` only ships an `amd64` image — no native `arm64` build —
so on an Apple Silicon Mac it needs `amd64` emulation, which isn't enabled by default. The
compose file pins `platform: linux/amd64` on that service so this fails loudly with this
error instead of silently hanging; it still needs one of the fixes below.

**Docker Desktop:**

1. Settings → General → enable **"Use Rosetta for x86/amd64 emulation on Apple Silicon"**.
2. Restart Docker Desktop.
3. `docker compose up -d --force-recreate tb-sparkplug-emulator`

**Colima:** Rosetta emulation is a VM-level setting, so it requires a VM restart (any
containers/images you have running are unaffected, they just restart):

```bash
colima stop
colima start --vz-rosetta
docker compose up -d --force-recreate tb-sparkplug-emulator
```

`--vz-rosetta` only works with colima's `vz` VM type (the default since colima ≥0.6 on
macOS 13+); check your current setup with `colima status`. If you're on the older `qemu`
VM type instead, register QEMU-based binfmt emulation inside the VM instead (slower than
Rosetta, but no VM restart needed):

```bash
docker run --privileged --rm tonistiigi/binfmt --install amd64
docker compose up -d --force-recreate tb-sparkplug-emulator
```

### Emulator can't connect / no NBIRTH appears

```bash
docker compose logs tb-sparkplug-emulator
```

Common cause: the broker wasn't healthy yet when the emulator started. `depends_on` with a
healthcheck condition should prevent this, but if the broker was slow to start, just
restart the emulator:

```bash
docker compose restart tb-sparkplug-emulator
```

### Check HiveMQ is actually listening

```bash
docker compose logs hivemq
nc -zv localhost 1883
```

### Reset everything

```bash
docker compose down
docker compose up -d
```

## Security Note

⚠️ This broker has no authentication and no TLS — local development / testing only. Do not
expose port 1883 beyond localhost/your test network.
