# Known Limitation and Disclaimer

As we already have a great C8Y API coverage for mappings, not all complex cases might be supported. 

Currently, the mappings to the following C8Y APIs are supported:

- inventory
- events (with binaries)
- measurements
- alarms
- operations (outbound to devices)

A mapping is defined of mapping properties and substitutions. The substitutions are mapping rules copying data from the incoming payload to the payload in the target system. These substitutions are defined using the standard JSONata as JSONata expressions. These JSONata expressions are evaluated in two different libraries:

1. `dynamic-mapper`: (nodejs) [npmjs JSONata](https://www.npmjs.com/package/jsonata) and
2. `dynamic-mapper-service` (java): [JSONata4Java](https://github.com/IBM/JSONata4Java)
   Please be aware that slight in differences in the evaluation of these expressions exist.

Differences in more advanced expressions can occur. Please test your expressions before you use advanced elements.