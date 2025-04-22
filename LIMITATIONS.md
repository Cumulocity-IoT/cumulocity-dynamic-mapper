# Known Limitation and Disclaimer

As we already have a very good C8Y API coverage for mapping not all complex cases might be supported. Currently, the
mappings to the following C8Y APIs are supported:

- inventory
- events
- measurements
- alarms
- operations (outbound to devices)

A mapping is defined of mapping properties and substitutions. The substitutions are mapping rules copying data from the incoming payload to the payload in the target system. These substitutions are defined using the standard JSONata as JSONata expressions. These JSONata expressions are evaluated in two different libraries:

1. `dynamic-mapping`: (nodejs) [npmjs JSONata](https://www.npmjs.com/package/jsonata) and
2. `dynamic-mapping-service` (java): [JSONata4Java](https://github.com/IBM/JSONata4Java)
   Please be aware that slight in differences in the evaluation of these expressions exist.

Differences in more advanced expressions can occur. Please test your expressions before you use advanced elements.

For Cumulocity MQTT Service currently no wildcards topics (e.g. `topic/#` or `topic/+` ) for Inbound Mappings / Subscriptions are allowed.

The [java library for JSONata](https://github.com/IBM/JSONata4Java) uses the words `and`, `or ` and `in` as reserved words in their [expression language](https://github.com/IBM/JSONata4Java/issues/317), hence they can be used as key in an JSON payload, see [issue](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper/issues/230).
