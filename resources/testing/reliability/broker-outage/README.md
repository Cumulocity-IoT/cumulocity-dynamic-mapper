# Outbound messages across a broker outage

Checks that outbound measurements are not lost when a message broker goes down and comes back. The
same publisher and checker work for **Pulsar**, **Kafka** and **MQTT**. A publisher creates an
increasing measurement every 5 seconds. A watcher reads the broker's outbound topic and flags any gap
in the sequence. You stop and start the broker by hand.

The MQTT scenario is also automated, with a simulated outage, in
[`integration/tests/independent/connectors/test-outbound-broker-outage.sh`](../../integration/tests/independent/connectors/test-outbound-broker-outage.sh).

| File | Purpose |
|------|---------|
| [publish-measurements.sh](publish-measurements.sh) | Creates measurement 1, 2, 3, ... every `INTERVAL` seconds (default 5) |
| [watch-pulsar.sh](watch-pulsar.sh), [watch-kafka.sh](watch-kafka.sh), [watch-mqtt.sh](watch-mqtt.sh) | Read the broker's outbound topic with a durable subscription, consumer group, or persistent session, and feed the values to the checker |
| [sequence-check.py](sequence-check.py) | Reports received values; flags `MISSED` only after a grace period; counts duplicates |

## The three brokers

| | Pulsar | Kafka | MQTT |
|---|---|---|---|
| Environment | [environments/pulsar](../../environments/pulsar/README.md) | [environments/kafka](../../environments/kafka/README.md) | [environments/hivemq](../../environments/hivemq/README.md) |
| Connector file | `connector/pulsar-connector.json` | `connector/kafka-connector.json` | `connector/mqtt-connector.json` |
| Mappings file | `mappings/sample-mappings.json` | `mappings/sample-mappings.json` | `mappings/sample-mappings.json` |
| Outbound mapping | Pulsar test - outbound ... | Kafka test - outbound ... | MQTT test - outbound ... |
| Outbound topic | `persistent://example-tenant/mqtt/pulsar-test-outbound-<EXT_ID>` | `kafka-test-outbound` (key = `<EXT_ID>`) | `mqtt/test/outbound/<EXT_ID>` |
| Start the broker | `docker compose up -d` | `docker compose up -d broker` | `docker compose up -d` |
| Watcher | `./watch-pulsar.sh` | `./watch-kafka.sh` | `./watch-mqtt.sh` |
| Stop the broker | `docker compose stop pulsar` | `docker compose stop broker` | `docker compose stop hivemq` |
| Start it again | `docker compose start pulsar` | `docker compose start broker` | `docker compose start hivemq` |
| Half-open variant | `docker pause pulsar-standalone` | `docker pause broker` | `docker pause hivemq` |
| Back to healthy after | 40 s or more | 45 s or more | about 20 s |
| Watcher keeps its place with | durable subscription `outage-watch` | consumer group `outage-watch` | persistent session `outage-watch` |

Run the compose commands in the environment's directory. Pulsar, Kafka UI and the Sparkplug environment
share host ports (`9090`, `1883`), so run one broker at a time or override the ports as the environment
READMEs describe.

## 1. Set up (once per broker)

1. Start the broker, the local backend and the UI as described in the environment's README
   (the cloud tenant cannot reach `localhost`, so the backend must run locally).
2. In the UI, import the connector file, enable it, and wait for CONNECTED.
3. Import the mappings file. Assign the **outbound** mapping to this connector and activate it.
   You do not need the inbound mapping.
4. Run `./publish-measurements.sh` once. It creates the device with external ID `outage-dev-001`
   (override with `EXT_ID`) if it is missing. Stop it with Ctrl-C.
5. In the mapper UI, add a device subscription for `MEASUREMENT` on that device.

The mapping is the same idea for all three: a Smart Function turns the measurement into
`{time, temperature, unit, source}` and chooses the topic itself, so the mappings have no `publishTopic`.

## 2. Run the test

Use three terminals, in this directory (`broker-outage/`). The examples use Kafka; swap the watcher and
the broker commands from the table.

**Terminal 1: watcher.** Start it first.
```bash
./watch-kafka.sh            # or ./watch-pulsar.sh, ./watch-mqtt.sh
```

**Terminal 2: publisher.**
```bash
./publish-measurements.sh
```
Within a few seconds the watcher prints `received 1`, `received 2`, and so on.

**Terminal 3: outage.** Let a few values arrive, then stop the broker (see the table):
```bash
cd ../../environments/kafka
docker compose stop broker
```
Wait one to two minutes. The publisher keeps printing `sent N`. The connector leaves CONNECTED on the
Connectors page, and the watcher prints `... retrying`. Then start the broker again:
```bash
docker compose start broker
```
Wait for the broker to become healthy. The connector reconnects on its own. Keep the publisher running
for another minute.

Vary the outage length. A short one (under a minute) checks reconnect. A longer one (3 minutes or more)
goes past the clients' internal retry windows (Kafka's producer gives up after 2 minutes by default),
which is where messages are most likely to be lost.

## 3. Read the result

The watcher prints each new value once. Reordering and duplicates are expected after an outage (the
mapper publishes from several threads, and delivery is at-least-once), so they do not count as loss:

- `received N (waiting for a..b)`: a higher value arrived first. The watcher waits `GRACE` seconds
  (default 30, set `GRACE=60` for slow recoveries) for the missing ones.
- `received N (late, out of order)`: a value it was waiting for arrived.
- `MISSED a..b`: still absent after the grace period. **This is the failure.** Those values were created
  in Cumulocity (the publisher printed `sent`) but never reached the broker. A `FAILED` line from the
  publisher means Cumulocity rejected the measurement, which is a different problem.
- Duplicates are only counted. Press Ctrl-C for the summary:
  `summary: distinct=14 duplicates=181 missed=none waiting=none`.
- `received N (older than the first value seen)` after starting the watcher on a topic that already holds
  messages is a replay of old values, not a problem.

**Pass:** `missed=none` after the broker is back and the publisher has run for another minute. A high
`duplicates` count is not a loss, but check it: every outbound message should normally arrive about once.
For Pulsar, `docker exec pulsar-standalone bin/pulsar-admin topics stats <topic>` shows the number of
`publishers`, which should be one per mapper, not dozens.

If you restart the publisher, its counter starts over at 1. Pass a start value
(`./publish-measurements.sh 1000`) so old values are not mistaken for new ones. To start the watcher from
scratch, use a new name: `SUB=run2 ./watch-pulsar.sh`, `GROUP=run2 ./watch-kafka.sh`,
`CLIENT_ID=run2 ./watch-mqtt.sh`.

## 4. Variations

- Different cadence: `INTERVAL=1 ./publish-measurements.sh`.
- Another device: set `EXT_ID` for the publisher and the Pulsar and MQTT watchers (Kafka uses one topic
  for all devices).
- MQTT on another port: `MQTT_PORT=1884 ./watch-mqtt.sh`, with the same port in the connector.

## Troubleshooting

- The watcher prints nothing although the publisher sends: check the device subscription (step 1.5), that
  the mapping is active and on the right connector, and that the connector is CONNECTED.
- Search the backend log for `Cannot publish`, `paused its device`, `resuming its device`.
- Pulsar exits with "Bookie handle is not available": `docker compose down -v`, then start again. This
  deletes the local data, so rerun the watcher to recreate the subscription.
- Kafka consumers never get messages and the broker log shows `__consumer_offsets` errors: see the
  Kafka README troubleshooting section.
- Stop the backend with the connector disabled, so no stale Notification 2.0 subscribers remain.
- Killing a watcher with `kill -9` can leave a consumer inside the Pulsar or Kafka container. The watchers
  clean up on normal exit and Ctrl-C. If a subscription is stuck "in use", restart the broker container.
