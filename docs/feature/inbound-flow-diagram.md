# Inbound processing chain — route diagram

A single end-to-end view of the Camel processor chain for inbound messages, from
`direct:processInboundMessage` through deserialization, enrichment, filtering, the
transformation branch (Flow / JSONata / Smart Function / Java extension) and consolidation.

This is a **supporting diagram**, not a feature page — it carries no requirements of its own.
The contract and the implementation detail live in
[mapping-processing-inbound.md](mapping-processing-inbound.md), whose mermaid diagrams cover
the dispatcher sequence and mapping-tree resolution instead of the processor chain shown here.

```
┌─────────────────────────────────┐
│     Inbound Message Entry       │
│   direct:processInboundMessage  │
└─────────────┬───────────────────┘
              │
              ▼
         ┌─────────┐         ┌──────────────────┐
         │Mappings?├──No────►│ Return Empty     │
         └────┬────┘         └─────────┬────────┘
              │Yes                     │
              ▼                        ▼
┌─────────────────────────────────┐    │
│   Filter & Split Mappings       │    │
│ • Active mappings only          │    │
│ • Deployed mappings only        │    │
│ • Parallel processing: true     │    │
└─────────────┬───────────────────┘    │
              │                        │
              ▼                        │
┌─────────────────────────────────┐    │
│     Common Processing Chain     │    │
│                                 │    │
│ ┌─────────────┐                 │    │
│ │Deserialize  │                 │    │
│ └─────┬───────┘                 │    │
│       ▼                         │    │
│ ┌─────────────┐                 │    │
│ │Mapping      │                 │    │
│ │Context      │                 │    │
│ └─────┬───────┘                 │    │
│       ▼                         │    │
│ ┌─────────────┐                 │    │
│ │Enrichment   │                 │    │
│ └─────────────┘                 │    │
└────────────────┬────────────────┘    │
                 │                     │
                 ▼                     │
     ┌──────────────────────┐          │
     │ Transformation Type? │          │
     └───────────┬──────────┘          │
                 │                     │
        ┌────────┼────────┬──────────┬─┤
        │        │        │          │ │
        ▼        ▼        ▼          ▼ ▼
   ┌─────────┐ ┌──────┐ ┌───────┐    │
   │Flow     │ │Code  │ │JSONata│    │
   │Function │ │Sub   │ │       │    │
   └────┬────┘ └──┬───┘ └───┬───┘    │
        │         │         │        │
        ▼         │         │        │
 ┌─────────────────┐        │        │
 │Flow Processor   │        │        │
 │• Execute JS     │        │        │
 │  onMessage()    │        │        │
 │• Create Flow    │        │        │
 │  Context        │        │        │
 └─────────┬───────┘        │        │
           │                │        │
    ┌─────────────┐         │        │
    │Ignore Check?│         │        │
    └──┬────────┬─┘         │        │
       │Yes     │No         │        │
       │        ▼           │        │
       │ ┌─────────────────┐│        │
       │ │Flow Result      ││        │
       │ │Processor        ││        │
       │ │                 ││        │
       │ │• Parse Results  ││        │
       │ │• Handle Lists   ││        │
       │ │• Process        ││        │
       │ │  CumulocityMsg  ││        │
       │ │• Resolve Device ││        │
       │ │• Create API     ││        │
       │ │  Requests       ││        │
       │ │• Set Payload    ││        │
       │ │  Hierarchically ││        │
       │ └─────────┬───────┘│        │
       │           │        │        │
       ▼           ▼        ▼        │
 ┌─────────────────────────────────┐ │
 │     Traditional Processing      │ │
 │                                 │ │
 │ Code:    JS Code Extraction     │ │
 │ JSONata: JSONata Extraction     │ │
 │                                 │ │
 └─────────────┬───────────────────┘ │
               │                     │
               ▼                     │
      ┌─────────────────┐            │
      │   Substitution  │            │
      │   Processor     │            │
      └─────────┬───────┘            │
                │                    │
                ▼                    ▼
┌─────────────────────────────────────┐
│         Final Processing            │
│                                     │
│ ┌─────────────┐  ┌─────────────────┐│
│ │Ignore       │  │Send & Process   ││
│ │Further      │  │• Create C8Y     ││
│ │Processing?  │  │  Requests       ││
│ │             │  │• Send to APIs   ││
│ └──────┬──────┘  └─────────┬───────┘│
│        │Yes                │        │
│        ▼                   ▼        │
│ ┌─────────────┐  ┌─────────────────┐│
│ │Log &        │  │Send &           ││
│ │Consolidate  │  │Consolidate      ││
│ └─────────────┘  └─────────────────┘│
└─────────────────┬───────────────────┘
                  │
                  ▼
            ┌─────────────┐
            │     END     │
            └─────────────┘
```
