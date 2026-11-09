# Concurrent device creation

Checks that a burst of messages for an external ID that does not exist yet creates **exactly one** device.

## Scenario

1. The test imports `mapping-IN-08-alarm.json` (a Smart Function that creates an alarm for the device named in
   topic level 1) with **"create non-existing device" switched on**. It moves the topic to
   `dmtest-burst-<pid>/+`, so it does not clash with a mapping you already run on `alarm/+`.
2. It checks that no device exists for the new external ID `dmtest-burst-<timestamp>`.
3. It sends the burst from several processes at once, all for the same external ID, so every message looks for
   the same missing device at the same moment (see Transports).
4. It waits until the mapping reports every message as received, waits a few seconds more for a late duplicate,
   then asserts:
   - the mapping received every message,
   - the device exists,
   - exactly one device named `device_c8y_Serial_<external id>` exists (the name the mapper gives an
     implicitly created device; if no device has that name, exactly one device was created during the burst),
   - the service log shows exactly one device creation,
   - the alarm landed on that device.
5. It reports whether the messages really raced (see below).

A broken creation lock shows up as two or more devices.

## Transports

| `DM_BURST_TRANSPORT` | How the burst is sent | Use it for |
|---|---|---|
| `mqtt` (default) | `mosquitto_pub` processes publishing to the public **HiveMQ broker** (`broker.hivemq.com:1883`) | The end-to-end MQTT path |
| `http` | `c8y api` processes POSTing to the HTTP connector (`<service>/httpConnector/<topic>`) | Testing the creation lock |

An MQTT burst often does **not** race. A run on 2026-11-05 showed the first message finishing the device creation
(about 100 ms) before the connector handed the next message to the dispatcher, so only one message ever found the
device missing. The test still passes then, and prints a warning. Over HTTP every request is handled on its own
thread, so the requests do compete for the device.

With `DM_BURST_HTTP_MODE=processes`, more clients does **not** mean a tighter burst: starting each `c8y` process takes
time, so 60 processes spread their first requests over tens of milliseconds, and the first request can finish creating
the device (about 100 ms) before the rest arrive. A run with 60 processes found only one message without a device, a run
with 40 found three. The default `workers` mode sends everything from one process and avoids this.

## Did the race happen?

A pass proves nothing if only one message saw the device missing. After the burst the test reads the mapping's
log through `GET /monitoring/service-logs?mapping=<identifier>` (needs `ROLE_DYNAMIC_MAPPER_ADMIN`) and counts

- the messages that looked the device up and did not find it (log line `External ID <id> not found`, one thread per message), and
- the device creations (log line `Creating new implicit device`).

It asserts exactly one creation. If fewer than two messages found no device it warns that the lock was not
exercised; with `DM_BURST_REQUIRE_RACE=true` that warning is a failure. If the log cannot be read (no admin role,
not collected yet, or another service instance handled the burst) the test says so and skips this check.

## Run

```bash
cd resources/testing/concurrent-device-creation
./test-concurrent-device-creation.sh                              # MQTT via HiveMQ, delete the test data afterwards
DM_BURST_TRANSPORT=http ./test-concurrent-device-creation.sh      # HTTP connector: a real race
DM_BURST_TRANSPORT=http DM_BURST_REQUIRE_RACE=true DM_BURST_MESSAGES=120 DM_BURST_CLIENTS=40 \
    ./test-concurrent-device-creation.sh                          # fail unless the messages raced
./test-concurrent-device-creation.sh --keep                       # keep mapping and device for inspection
./test-concurrent-device-creation.sh --validate-only              # check the environment only
```

Prerequisites: the Dynamic Mapper service is running, the `c8y` CLI has an active session with
`ROLE_DYNAMIC_MAPPER_ADMIN`, and `jq` is installed. For `mqtt` you also need `mosquitto_pub` and an active MQTT
connector connected to the same broker; for `http` the built-in HTTP connector is used. The test reuses the harness
of `../integration/test-harness.sh`.

| Variable | Default | Meaning |
|----------|---------|---------|
| `DM_BURST_TRANSPORT` | `mqtt` | `mqtt` or `http` |
| `DM_BURST_MESSAGES` | 60 | Messages in the burst |
| `DM_BURST_CLIENTS` | 12 | Parallel client processes |
| `DM_BURST_SETTLE_SECS` | 40 | Maximum wait for the burst to be processed |
| `DM_BURST_REQUIRE_RACE` | `false` | Fail when fewer than two messages raced |
| `DM_BURST_HTTP_MODE` | `workers` | `http` only. `workers`: one `c8y api` process with `DM_BURST_CLIENTS` parallel workers (at most 50, the limit of `c8y`). `processes`: one `c8y` process per client |
| `MQTT_HOST` / `MQTT_PORT` | `broker.hivemq.com` / `1883` | Broker for the `mqtt` transport |

The Cumulocity MQTT Service mode (`DM_BROKER_MODE=c8y-mqtt-service`) is not supported for `mqtt`: it allows one
MQTT client per certificate, and this test needs several. The test skips itself in that mode; use `http`.

Test data is named `dmtest-*`, `test-*`, and `device_c8y_Serial_dmtest-*`. `../integration/run-tests.sh
--cleanup-only` sweeps `test-*` and `dmtest-*`, but not the `device_c8y_Serial_dmtest-*` devices, so remove those by
hand after a `--keep` run.
