# Measuring the live set of a locally running mapper

Goal: find the heap the service really holds on to (the **live set**: heap in use right after a full
GC) under realistic inbound load, so `-Xmx` and the container memory request can be set from data.
Background: `docs/planning/IMPLEMENTATION-PLAN-GRAALVM-JS-IMPROVEMENTS.md` §4.3, item 1.

Scripts in this folder:

| Script | Purpose |
|---|---|
| `run-live-set-load.sh` | Sets up 3 Smart Function mappings (02, 03, 04 of the integration suite's `smartfunction` group), creates devices, publishes through an MQTT broker for a given time (public HiveMQ by default), cleans up. |
| `measure-live-set.sh` | Every N seconds: `jcmd GC.run` + `GC.heap_info`; writes a CSV and prints a summary with a suggested `-Xmx`. |

## 0. Preconditions

- Exactly **one** mapper instance serves the tenant while you measure. A second (deployed) instance
  would take part of the messages and skew the numbers. Stop or scale down the deployed one first.
- `application-dev.properties` is set up (bootstrap user/tenant/baseURL), see "hosted deployment".
- c8y CLI session for the same tenant, `mosquitto_pub`, `jq`, and a full JDK (`jcmd`).
- Internet access to the public MQTT broker of an **enabled MQTT connector** in the mapper (e.g. HiveMQ).
- Why not the Cumulocity MQTT Service: a locally running mapper cannot reach it. Its connector talks
  Pulsar to `localhost:6650` (`C8Y_BASEURL_PULSAR` in `application-dev.properties`), which only exists
  in the cloud — you would see `Connection refused: localhost/127.0.0.1:6650`.

## 1. Start the service with production-like JVM flags

The pom's `spring-boot-maven-plugin` sets `-Dspring.profiles.active=dev`; passing your own
`jvmArguments` replaces that, so repeat it:

```bash
cd dynamic-mapper-service
export JAVA_HOME=~/.sdkman/candidates/java/25.0.3-zulu
/opt/homebrew/bin/mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dspring.application.admin.enabled=true -Dspring.profiles.active=dev \
   -Xmx2g -XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxMetaspaceSize=500m -XX:NativeMemoryTracking=summary"
```

`-Xmx2g` is deliberately generous: the point is to *observe* the live set, not to constrain it.
(The deployed entrypoint derives `-Xmx` from the memory request, about 81 % of it; the live set does not depend on that.)

Wait until the log shows the tenant's connectors and mappings loaded.

## 2. Start the sampler (second terminal)

```bash
resources/testing/performance/graalvm-memory/measure-live-set.sh --interval 60
# --pid PID if it finds more than one JVM; --out FILE.csv; --duration SEC
```

Let it run for a few minutes **before** the load to get the idle baseline.

## 3. Start the load (third terminal)

```bash
resources/testing/performance/graalvm-memory/run-live-set-load.sh --local --devices 30 --duration 1800
```

- `--local` sends the REST setup calls straight to `localhost:8080` (not through the tenant's
  `/service/...` route, which would reach the deployed instance). Without it, the harness talks to
  whatever the tenant routes to.
- The broker is taken from the first **enabled MQTT connector** in the mapper (host, port, TLS) — your
  tenant's "Hive" connector is `broker.hivemq.com:8883` over TLS. Set `MQTT_HOST`/`MQTT_PORT` (and
  `MQTT_TLS=true`, `MQTT_CAFILE`) to override. The harness refuses to run if no enabled MQTT connector
  matches the broker it publishes to (`SKIP: No enabled MQTT connector is configured for ...`).
- With a public broker `--publishers` defaults to 4 parallel publisher
  processes (each publish opens its own connection, so expect a few tens of messages per second at most).
  `--publishers 8` or more raises the concurrency; the log shows sent/failed counts per worker.
- Public broker caveat: the topics are the integration suite's fixed ones (`testSmartInbound/...`,
  `flowState/...`), so strangers using the same topics would reach your mappings. Device ids are
  unique per run, so such messages produce nothing useful but add noise.
- To drive the Cumulocity MQTT Service instead (only when the Pulsar endpoint is reachable, e.g.
  against a deployed instance): `DM_BROKER_MODE=c8y-mqtt-service ./run-live-set-load.sh ...`. The clientId is
  then the certificate CN, so there is one publisher and `--publishers` is forced to 1.
- `--keep` keeps mappings and devices; otherwise everything is removed afterwards. Ctrl-C also cleans up.
- `--validate-only` checks the environment and exits.

Use at least 30 minutes so caches (inventory cache, external-id cache, flow state) reach steady state.
Increase `--devices` (e.g. 200–500) to see how the live set scales with the number of devices.

## 4. Read the result

The sampler prints a line per tick and, on Ctrl-C or `--duration`, a summary:

- **live heap MB, first/last/max**: the number that matters.
- **trend MB/hour**: flat means steady state. A sustained climb after the caches have filled
  (compare against `inventoryCacheSize`, `inboundExternalIdCacheSize`) points to a leak — keep going
  and take a heap dump (`jcmd PID GC.heap_dump file.hprof`).
- **peak metaspace / RSS**: RSS minus heap shows the native overhead to budget for.

Then:

```
-Xmx            ≈ 2 × max live set (never below 512m)
container memory ≈ Xmx + metaspace + 300–400 MB, checked against the RSS you saw
```

In the deployed container `entrypoint.sh` derives `-Xmx` from `MEMORY_LIMIT` (about 81 %) and overwrites
`JAVA_MEM`; its `-Xmx` comes after `JAVA_OPTS`, so the practical knob is the `memory` value in
`cumulocity.json`. Lower it in steps and watch the platform's memory graph and container restarts. Rule of thumb, not a guarantee: the production container
(Alpine, `jlink` JDK) is not identical to your laptop run.

## What this does not cover

- **Production-scale concurrency.** A few publisher processes give a handful of pooled contexts per
  mapping. The pool measurement (`GraalVMContextMemoryMeasurementTest`) puts a context at about
  85 KB, so the concurrency effect is small, but a multi-tenant or high-rate production system sees
  more. Raise `--publishers` to probe it.
- **Other connectors and mapping types** (JSONata, outbound, Kafka, ...) — their caches and
  buffers are not exercised.
- **Real tenant shape.** Multiply by the real number of tenants (each has its own caches, engine and
  pools) before choosing the production request.

## Troubleshooting

- *REST calls fail with 401/404 when using `--local`*: check the local service answers with the
  session's credentials: `c8y api GET /mapping --host localhost:8080`.
- *Messages are not processed*: confirm the MQTT connector shows CONNECTED
  (`GET /monitoring/status/connectors`, or the UI) and that no deployed instance is competing for the tenant.
- *`jcmd` cannot attach*: run it as the same OS user as the service, on the same machine.
