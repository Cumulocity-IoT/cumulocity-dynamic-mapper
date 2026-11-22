# Reliability testing

Manual tests for failure behavior of the dynamic-mapper-service: a broker or the Cumulocity
platform going away, and concurrency under cancellation.

| Directory | Scenario |
|-----------|----------|
| [broker-outage/](broker-outage/) | A Pulsar, Kafka or MQTT broker goes down while outbound measurements flow; a publisher and a watcher show whether any message is lost |
| [c8y-unavailable/](c8y-unavailable/) | Cumulocity returns 502/401 after a connector reconnect; a mitmproxy addon (`fault_inject.py`) injects the faults |
| [graalvm-cancel-race/](graalvm-cancel-race/) | Instructions for a concurrency test around GraalVM context borrowing under worker cancellation (not implemented) |
| [tools/](tools/) | `debug_local_service.sh` runs the service locally with a debug port; `start.md` is a local scratch file (gitignored) |

Which tool for which failure: mitmproxy sits in front of the Cumulocity HTTP API only, so it fits
`c8y-unavailable/`. A broker outage is simulated on the broker itself (stop or pause its container).
