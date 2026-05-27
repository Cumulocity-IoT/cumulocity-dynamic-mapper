# Known Limitation and Disclaimer

As we already have a great C8Y API coverage for mappings, not all complex cases might be supported. 

Currently, the mappings to the following C8Y APIs are supported:

- inventory
- events (with binaries)
- measurements
- alarms
- operations (outbound to devices)

A mapping is defined by mapping properties and substitutions. The substitutions are mapping rules that copy data from the incoming payload to the target payload. Three transformation types are supported:

1. **JSONata expressions** — evaluated in two different libraries depending on context:
   - `dynamic-mapper-ui` (Angular/browser): [npmjs JSONata](https://www.npmjs.com/package/jsonata)
   - `dynamic-mapper-service` (Java): [JSONata4Java](https://github.com/IBM/JSONata4Java)

   Slight differences in the evaluation of advanced expressions can occur between these two libraries. Please test your expressions thoroughly before using advanced features.

2. **Smart Functions** — JavaScript callbacks executed in a GraalVM polyglot sandbox at runtime. Written in TypeScript using `dynamic-mapper-smart-function` for type safety, then compiled to JavaScript and pasted into the mapping editor. See [USERGUIDE.md](USERGUIDE.md) for details.

3. **Java Extensions** — custom processor implementations (`ProcessorExtensionInbound` / `ProcessorExtensionOutbound`) packaged as JARs and uploaded to Cumulocity. See [EXTENSIONS.md](EXTENSIONS.md) for details.