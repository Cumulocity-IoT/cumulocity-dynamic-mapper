# De-duplication across 50 devices

`test-dedup-50-devices.sh` checks the de-duplication of inbound messages (issue #692) under load: 50 devices, each sending 10 distinct messages, every message sent twice.

## What it checks

| Check | Expected |
|-------|----------|
| Messages that reach the mapping | 1,151 by default: 1,050 + 1 control per device in phase 1, 2 per device in phase 2 |
| Measurements created | 600: 500 distinct, 50 controls, 50 re-processed after the window |
| Repeats dropped (`messagesDuplicate`) | 550: 500 in phase 1, 50 in phase 2 |
| Devices created | 50 |
| Devices with exactly 12 measurements | 50 |

Without phase 2 (`DM_DD_EXPIRY=false`) the numbers are 1,051 / 550 / 500 / 11 per device.

The second copy of each message is the **same JSON value written differently**: members in another order (also in the nested `meta` object) and `21.0` written as `21`. If the normalization works, the copy is dropped. One control message per device repeats sequence number 1 with another temperature. It must pass, so a key that ignores the payload cannot satisfy the test.

## Transports

`DM_DD_TRANSPORT` selects how the messages reach the mapper. The expected numbers are the same for both.

| Transport | How | What it adds |
|-----------|-----|--------------|
| `mqtt` (default) | `mosquitto_pub` through the MQTT connector | The connector hands messages over in order, so an original and its copy are processed one after the other. |
| `http` | One `curl --parallel` process posts to `<service>/httpConnector/<topic>`, `DM_DD_HTTP_PARALLEL` (default 50) requests in flight | Every request runs on its own thread, so an original and its copy are processed at the same moment. This checks that exactly one of two simultaneous copies passes. |

```bash
DM_DD_TRANSPORT=http ./test-dedup-50-devices.sh
```

The HTTP transport sends with `curl`, not `c8y`, because `c8y` parses the body and writes it again, which would remove the member order and the `21.0` that the test depends on. It needs `C8Y_HOST` and either `C8Y_TOKEN` or `C8Y_USER` and `C8Y_PASSWORD` (with `C8Y_TENANT`); with `DM_SERVICE_HOST` set it talks to a local mapper. `DM_DD_INTERVAL` does not apply: the requests are sent as fast as the parallelism allows. In phase 2 the two requests of a device are also concurrent, so either spelling can win. The counts are the same, because one of them is processed and the other is dropped.

The device id is a member of the payload (`"device"`), not a topic level, so both transports run the same Smart Function with the whole payload as the key.

## Phase 2: window expiry

Phase 1 sends every copy within milliseconds of its original, so it only needs a window of a few seconds. Phase 2 checks that the window ends. After `DM_DD_WINDOW` + 3 seconds (default 33 s) every device sends message 1 again, in the reordered spelling. It must be processed again, because the old fingerprint expired. The device then sends message 1 once more in the first spelling. It must be dropped, because the new fingerprint was recorded. This also shows that both spellings map to one key after expiry.

Keep `DM_DD_WINDOW` short, because the run waits for it. Do not go below about 10 seconds: under load, a copy that is processed later than the window after its original would pass in phase 1 and fail the test.

## Run

```bash
./test-dedup-50-devices.sh                 # defaults: 50 devices, 10 messages, 1 copy
DM_DD_DEVICES=20 DM_DD_INTERVAL=0.05 ./test-dedup-50-devices.sh --keep
```

See the header of the script for all `DM_DD_*` variables. The prerequisites are those of [../../integration/](../../integration/): a running service, an active MQTT connector, an authenticated `c8y` CLI, `jq`, and `mosquitto_pub`. In Cumulocity MQTT Service mode the devices are sent one after the other, because the service allows one client per certificate.

## What "the payload is normalized" means

Findings from `MessageDeduplicationService.keyText`, which the filter function (`$isDuplicate`) and the Smart Function (`context.isDuplicate`) both call before hashing (SHA-256, first 128 bits):

| Key type | Normalized? |
|----------|-------------|
| Object | Yes. Members are sorted by name at every level, so member order does not matter. |
| Number | Yes. `5.0`, `5`, `5L`, and `1.0E15` have one text form (`5`, `1000000000000000`). |
| Array | Order is kept, which is correct: `[1,2]` and `[2,1]` are different values. |
| Text | **No. Used as written.** `JSON.stringify(payload)` or `$string($)` keeps the member order and whitespace of the sender. |
| Unicode text | **No.** Composed and decomposed forms of one character (NFC and NFD) give different hashes. |

So two semantically equal payloads get the same hash **only if the key is an object (or number or boolean) rather than a string**. For the whole payload, pass the object (`ctx.isDuplicate(payload, 60)`). In a JSONata filter, pass the object too, not `$string(...)`. A string key built from selected fields (`deviceId & "|" & time`) is fine, because the fields are values, not serialized JSON.

The unit tests `keyTextOfObjectsDoesNotDependOnMemberOrder` and `anEquivalentNumberIsOneKeyWhateverItsJavaType` cover the Java side. This script covers the whole path: MQTT, JSON parsing, GraalVM conversion, key text, hash, and the shared store.

## Limits

- The state is in memory, per mapping, and not shared between service instances. With several instances, the same message can pass once per instance.
- The window starts at the first sighting and does not slide. A repeat that arrives after the window ends is processed, by design.
