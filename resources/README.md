# resources/

Supporting assets that are not part of any build artifact: test suites, sample data, developer
tooling and documentation images.

Folders are organised by **topic**, not by file type. A shell script belongs next to the thing it
is about, not in a general "scripts" folder — that grouping is what this layout replaced.

| Folder | Contains |
|--------|----------|
| [`testing/integration/`](testing/integration/) | End-to-end shell tests driven by `run-tests.sh`. Needs the `c8y` CLI and a deployed microservice. See [docs/testing.md](../docs/testing.md). |
| [`testing/performance/`](testing/performance/) | Load and profiling assets: JMeter profile, Python MQTT generators, async-profiler scripts, and the mappings they drive. |
| [`testing/environments/`](testing/environments/) | Docker Compose environments for the brokers the tests run against — AMQP, HiveMQ, Kafka, Pulsar, Webhook — plus a local microservice setup. |
| [`testing/fixtures/`](testing/fixtures/) | Setup scripts that create the devices and subscriptions tests expect. |
| [`testing/reliability/`](testing/reliability/) | Backend-unavailable / fault-injection testing: a mitmproxy addon that injects HTTP faults, the script to run the service locally against it, and the procedure tying them together. |
| [`tools/mgmt/`](tools/mgmt/) | `dm.sh`, the CLI for managing a deployed mapper. |
| [`tools/protobuf/`](tools/protobuf/) | Regenerates the Protobuf descriptors used by the SparkPlug B and Protobuf sample extensions. |
| [`samples/`](samples/) | Importable sample mappings (JSON) plus their documented overview (xlsx/PDF), and a LoRa decoding example. |
| [`openAPI/`](openAPI/) | **Generated.** REST API reference produced by openapi-generator; regenerated from the service, not edited by hand. |
| `image/` | Screenshots used by the documentation. |
| `image-optimized/` | **Generated and gitignored.** Compressed copies of the images listed in `dynamic-mapper-ui/cumulocity.config.ts`, produced by that module's `prebuild` step. |

## Conventions

- **Images referenced from the in-app documentation must be listed in
  `dynamic-mapper-ui/cumulocity.config.ts`.** Markdown under `dynamic-mapper-ui/public/docs/` uses
  the repo-relative form `../../../resources/image/<name>.png` so the link resolves on GitHub; the
  app rewrites it to the bundled path at render time. A file that is referenced but not listed in
  the config will 404 in the app while looking correct on GitHub.
- `image-optimized/` is only ever added to, never pruned. Deleting a screenshot leaves its
  compressed copy behind — remove that too.
- Anything under `openAPI/` is generated output. Change the service, then regenerate.
