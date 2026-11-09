---
title: Cumulocity API connector
---

### Cumulocity API connector {#cumulocity-api-connector}

The **Cumulocity API** connector is an outbound-only connector for the REST API of the tenant that the mapper runs on. Use it
to create, update, and delete managed objects, events, alarms, and measurements from an outbound mapping, for example to
receive an event and write a decoded measurement. It supports POST, PUT, PATCH, and DELETE, and the QoS level at least once.

It is a preconfigured variant of the [Webhook connector](/c8y-pkg-dynamic-mapper/introduction/connector-webhook) with the
setting **Use Cumulocity API of this tenant** switched on. It has no properties to configure. It uses the microservice
credentials and the internal endpoint of the tenant. Wildcards are allowed in outbound publish topics.
