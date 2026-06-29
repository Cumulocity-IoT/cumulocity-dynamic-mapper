# Local Performance Profiling — Run Guide

## Directory layout

```
performance/
  mqtt/           Python MQTT load tests (loadTest_01–04.py)
  jmeter/         JMeter test plan
  mappings/       Mapping definitions for C8Y import
  profiler/       Local JVM profiling (this directory)
    setup.sh                   One-time setup: device + mapping
    test-dynamic-mapper.sh     Start service with JVM profiling
    test-generator.sh          HTTP load test against /test/mapping
    warmup-outbound.sh         Warmup runs
    test-inbound-mapping_03.json   Smart Function mapping definition
    test-inbound-request_01.json   Smart Function (ExtractFromSource)
    test-inbound-request_02.json   JSONata mapping
    test-inbound-request_03.json   Smart Function (inventory enrichment)
    test-outbound-request_01.json  Outbound EMU meter mapping
```

---

## Prerequisites

- `go-c8y-cli` session active: `eval $(c8y sessions login)`
- JAR built: `cd dynamic-mapper-service && mvn clean package -DskipTests`

---

## Step 0 — Start the service

```bash
./test-dynamic-mapper.sh
```

Profiles `memtest,dev` are activated. Credentials are read from `application-dev.properties`.
The PID and JFR attach command are printed at startup.

---

## Step 1 — One-time setup

Run once (or after a tenant reset) to create the test device and load the Smart Function mapping:

```bash
./setup.sh
```

This:
- Creates device **Sensor Berlin 01** with `c8y_Sensor.type.voltage=true`
- Binds external ID `sensor-berlin-01` (type `c8y_Serial`)
- Loads `test-inbound-mapping_03.json` into the local service
- Patches `test-inbound-request_03.json` with the real device ID

---

## Step 2 — Warm up the JVM

Let the JVM reach steady-state before capturing any profiles.

```bash
# Warm up outbound processor
./warmup-outbound.sh test-outbound-request_01.json 50

# Warm up inbound processor
./test-generator.sh test-inbound-request_02.json 50 10 50
```

Arguments for `test-generator.sh`: `<request-file> <iterations> <report-interval> <delay-ms>`

---

## Step 3 — Start JFR capture

The service PID and the exact `jcmd` attach command are printed at startup.

```bash
jcmd <PID> JFR.start name=profiling filename=/tmp/profile_run1.jfr settings=profile
```

---

## Step 4 — Run the load test

```bash
# JSONata mapping — moderate load
./test-generator.sh test-inbound-request_02.json 1000 100 50

# Smart Function mapping with inventory enrichment
./test-generator.sh test-inbound-request_03.json 1000 100 50

# High throughput — no delay
./test-generator.sh test-inbound-request_02.json 2000 200 0
```

---

## Step 5 — Capture JFR snapshot and check memory

```bash
# List active recordings (shows name and status)
jcmd <PID> JFR.check

# Stop the named recording and write the file
jcmd <PID> JFR.stop name=profiling filename=/tmp/profile_run1.jfr

jcmd <PID> GC.heap_info
jcmd <PID> VM.native_memory
```

Open `/tmp/profile_run1.jfr` in **JDK Mission Control** (`jmc`).

---

## What to look for

| Goal | Approach |
|------|----------|
| Memory leaks | Run 5000+ iterations; compare heap before/after `jcmd <PID> GC.run` |
| Latency hotspots | Check avg/min/max from `test-generator.sh`; flame graph in JMC |
| CPU hotspots | JFR → Method Profiling view |
| GC pressure | `/tmp/gc-logs/gc_<timestamp>.log` or JMC → GC view |

---

## MQTT load tests (optional)

For end-to-end throughput tests via the broker:

```bash
# JSONata mapping
python3 ../mqtt/loadTest_03.py

# Smart Function mapping
python3 ../mqtt/loadTest_04.py
```

Requires `C8Y_TENANT`, `C8Y_USERNAME`, and `C8Y_HEADER_AUTHORIZATION` in the environment (set by `c8y sessions login`).
