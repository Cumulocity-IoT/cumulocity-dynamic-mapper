---
title: HTTP connector
---

### Default HTTP Connector {#http-connector}

The **Default HTTP Connector** (connector type **HTTP Endpoint**) does not need to be created manually. The
service creates it automatically for every tenant at microservice startup. It is reachable at
`https://<YOUR_CUMULOCITY_TENANT>/service/dynamic-mapper-service/httpConnector/<MAPPING_TOPIC>`: the path segment
after `.../httpConnector/` is used directly as the mapping topic. For example, a JSON payload POSTed to
`.../httpConnector/temp/berlin_01` is resolved against a mapping with mapping topic `temp/berlin_01`. The
**Cut off leading slash** property (on by default) removes a leading `/` of that sub path before the lookup.

The sender needs the `ROLE_DYNAMIC_MAPPER_HTTP_CONNECTOR_CREATE` role and authenticates against Cumulocity. The
connector has no credentials of its own. The endpoint answers `200` only after the mapping pipeline succeeded. A
pipeline failure or a timeout returns `500`, so the sender can retry. Errors while handing over the message, for
example when no HTTP connector is configured, return `400`.

![HTTP connector settings](../../../resources/image/Dynamic_Mapper_Connector_Http.png "Default HTTP Connector (inbound) configuration properties.")
