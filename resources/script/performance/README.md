# Performance Tests

## Structure

| Directory | Contents |
|-----------|----------|
| `mqtt/` | Python MQTT load tests (`loadTest_01–04.py`) |
| `jmeter/` | JMeter test plan |
| `mappings/` | Mapping definition files for C8Y import |
| `profiler/` | Local JVM profiling — service launcher, HTTP load tests, setup |

## Quick start

See **[profiler/TEST.md](profiler/TEST.md)** for the full profiling workflow.

### MQTT load tests

```bash
pip install -r requirements.txt
eval $(c8y sessions login)

# JSONata via MQTT
python3 mqtt/loadTest_03.py

# Smart Function via MQTT
python3 mqtt/loadTest_04.py
```

### Local HTTP profiling

```bash
cd profiler
./test-dynamic-mapper.sh   # start service
./setup.sh                 # one-time: create device + load mapping
./test-generator.sh test-inbound-request_03.json 1000 100 50
```
