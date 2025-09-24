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
    ┌────────────┼──────────┬────────┬─┤
    │            │          │        │ │
    ▼            ▼          ▼        ▼ ▼
┌────────┐  ┌─────────┐ ┌──────┐ ┌───────┐
│Snooping│  │Flow     │ │Code  │ │JSONata│
│        │  │Function │ │Sub   │ │       │
└───┬────┘  └────┬────┘ └──┬───┘ └───┬───┘
    │          │           │         │
    │          ▼           │         │
    │   ┌─────────────────┐│         │
    │   │Flow Processor   ││         │
    │   │• Execute JS     ││         │
    │   │  onMessage()    ││         │
    │   │• Create Flow    ││         │
    │   │  Context        ││         │
    │   └─────────┬───────┘│         │
    │             │        │         │
    │      ┌─────────────┐ │         │
    │      │Ignore Check?│ │         │
    │      └──┬────────┬─┘ │         │
    │         │Yes     │No │         │
    │         │        ▼   │         │
    │         │ ┌─────────────────┐  │
    │         │ │Flow Result      │  │
    │         │ │Processor        │  │
    │         │ │                 │  │
    │         │ │• Parse Results  │  │
    │         │ │• Handle Lists   │  │
    │         │ │• Process        │  │
    │         │ │  CumulocityMsg  │  │
    │         │ │• Resolve Device │  │
    │         │ │• Create API     │  │
    │         │ │  Requests       │  │
    │         │ │• Set Payload    │  │
    │         │ │  Hierarchically │  │
    │         │ └─────────┬───────┘  │
    │         │           │          │
    │         ▼           ▼          ▼
    │   ┌─────────────────────────────────┐
    │   │     Traditional Processing      │
    │   │                                 │
    │   │ Code:    JS Code Extraction     │
    │   │ JSONata: JSONata Extraction     │
    │   │                                 │
    │   └─────────────┬───────────────────┘
    │                 │
    │                 ▼
    │        ┌─────────────────┐
    │        │   Substitution  │
    │        │   Processor     │
    │        └─────────┬───────┘
    │                  │
    ▼                  ▼
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