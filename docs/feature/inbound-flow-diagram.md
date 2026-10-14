# Inbound processing chain — flow diagram

A single end-to-end view of the processor chain for inbound messages, from
`InboundMessageDispatcher` through `InboundMessageRouter`: mapping selection, deserialization,
enrichment, filtering, the transformation branch (internal Protobuf / Java extension / JSONata /
Smart Function) and consolidation.

This is a **supporting diagram**, not a feature page — it carries no requirements of its own.
The contract and the implementation detail live in
[mapping-processing-inbound.md](mapping-processing-inbound.md), whose mermaid diagrams cover
the dispatcher sequence and mapping-tree resolution in addition to the processor chain shown here.

```mermaid
flowchart TD
    entry["InboundMessageDispatcher\nonMessage / onTestMessage\nresolve mappings, QoS, timeout"] --> submit["Submit to virtual thread pool\nreturn ProcessingResultWrapper"]
    submit --> router["InboundMessageRouter.processInboundMessage"]
    router --> valid{"Valid mappings?\nactive and deployed\non the connector"}
    valid -- none --> empty["Return empty list"]
    valid -- one --> single["processSingleInboundMapping\ninline"]
    valid -- several --> par["processSingleInboundMapping\none virtual thread per mapping\njoin results in order"]
    par --> single

    subgraph perMapping["Per mapping"]
        single --> deser["DeserializationInboundProcessor\nbuild ProcessingContext"]
        deser --> enrich["EnrichmentInboundProcessor"]
        enrich --> filter["FilterInboundProcessor"]
        filter --> ign1{"ignoreFurtherProcessing?"}
        ign1 -- yes --> consolidation
        ign1 -- no --> branch{"Branch by type"}

        branch -- "PROTOBUF_INTERNAL" --> pb["InternalProtobufProcessor"]
        branch -- "EXTENSION_JAVA" --> ext["ExtensibleInboundProcessor"]
        branch -- "JSONata or default" --> js["JSONataInboundProcessor"]
        branch -- "SMART_FUNCTION" --> flow["FlowInboundProcessor\nrun JavaScript onMessage"]
        branch -- "no match: warn, use JSONata" --> js

        pb --> subst["SubstitutionResultInboundProcessor"]
        js --> subst
        ext --> ign2{"ignoreFurtherProcessing?"}
        ign2 -- no --> extres["ExtensibleResultInboundProcessor"]
        extres --> ign3{"ignoreFurtherProcessing?"}
        flow --> ign4{"ignoreFurtherProcessing?"}
        ign4 -- no --> flowres["FlowResultInboundProcessor"]
        subst --> ign5{"ignoreFurtherProcessing?"}

        ign2 -- yes --> consolidation
        ign3 -- yes --> consolidation
        ign4 -- yes --> consolidation
        ign5 -- yes --> consolidation
        ign3 -- no --> send
        ign5 -- no --> send
        flowres --> send["SendInboundProcessor\nresolve ids, create requests, call Cumulocity"]
        send --> consolidation["ConsolidationProcessor\nbook outcome, close GraalVM context"]
    end

    errors["Any exception in the per-mapping pipeline:\nlog, add error to context, consolidate"] -.-> consolidation
    consolidation --> done["Return ProcessingContext to dispatcher\n422 handling and optional single resend"]
```

Notes:

- `ConsolidationProcessor` is the single terminal step of every mapping, including filtered
  and failed ones.
- After `FlowResultInboundProcessor` there is no further ignore check; the context goes straight
  to `SendInboundProcessor`.
