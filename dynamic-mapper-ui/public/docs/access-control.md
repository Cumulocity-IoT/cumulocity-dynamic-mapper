---
title: Managing permissions
---

### Managing permissions for Dynamic Mapper features {#access-control}

Dynamic Mapper ships three roles. Assign them in **Administration → Role Management**, either to a global role or
to a specific user group.

| Role | Grants |
|---|---|
| `ROLE_DYNAMIC_MAPPER_CREATE` | Everything to do with mappings: create, edit, delete, activate, debug, test, versions and drafts, outbound subscriptions, the Message Explorer and client relations. |
| `ROLE_DYNAMIC_MAPPER_ADMIN` | Everything the create role grants, **plus** connectors, service configuration, code templates, processor extensions, the service logs of your tenant and the maintenance operations. |
| `ROLE_DYNAMIC_MAPPER_HTTP_CONNECTOR_CREATE` | Permission to POST payloads into the Default HTTP Connector endpoint. Independent of the other two (see below). |

`ROLE_DYNAMIC_MAPPER_ADMIN` implies `ROLE_DYNAMIC_MAPPER_CREATE`; you do not need to assign both.

A user with **none** of these roles can still open the application and read mappings, connectors (secrets are
masked), the service configuration, monitoring and the deployment map. Exceptions are the service logs, which need
`ROLE_DYNAMIC_MAPPER_ADMIN`, and the processor extension list, the Message Explorer, and the mapping drafts and
versions, which need at least `ROLE_DYNAMIC_MAPPER_CREATE`. What they cannot do is change anything.

:::caution
Assign the minimum role that does the job. Most users need only `ROLE_DYNAMIC_MAPPER_CREATE`. Reserve
`ROLE_DYNAMIC_MAPPER_ADMIN` for the people who manage infrastructure: connectors, service configuration, and
shared code templates.
:::

#### What each role can do

| Feature | No role | Create | Admin |
|---|:---:|:---:|:---:|
| **Mappings** | | | |
| Read mappings, monitoring, deployment map | ✓ | ✓ | ✓ |
| Create / edit / delete a mapping | – | ✓ | ✓ |
| Activate / deactivate a mapping | – | ✓ | ✓ |
| Debug a mapping, apply a mapping filter | – | ✓ | ✓ |
| Test a mapping (including *Live Test*) | – | ✓ | ✓ |
| Drafts, versions, publish, version notes, the version counts of **Monitoring → Versions** | – | ✓ | ✓ |
| Import mappings | – | ✓ | ✓ |
| Update a mapping's transformation code | – | ✓ | ✓ |
| Add sample mappings, reload mappings, refresh mapping status | – | ✓ | ✓ |
| **Outbound** | | | |
| Read subscriptions | ✓ | ✓ | ✓ |
| Create / edit / delete subscriptions, resync a type | – | ✓ | ✓ |
| Read client relations | ✓ | ✓ | ✓ |
| Change client relations | – | ✓ | ✓ |
| **Message Explorer** | | | |
| Start / stop a session, read or clear captured messages | – | ✓ | ✓ |
| **Connectors** | | | |
| Read connectors and their status | ✓ | ✓ | ✓ |
| Create / edit / delete a connector | – | – | ✓ |
| Connect / disconnect a connector | – | – | ✓ |
| Import connectors (exporting is open to everyone) | – | – | ✓ |
| **Configuration** | | | |
| Read service configuration | ✓ | ✓ | ✓ |
| Edit, export, or import service configuration | – | – | ✓ |
| Read code templates | ✓ | ✓ | ✓ |
| Create / edit / delete code templates, reset system templates | – | – | ✓ |
| Read processor extensions | – | ✓ | ✓ |
| Delete a processor extension, reload extensions | – | – | ✓ |
| **Monitoring** | | | |
| Read the [service logs](/c8y-pkg-dynamic-mapper/introduction/monitoring#service-logs) of your tenant | – | – | ✓ |
| **Maintenance** | | | |
| Reset statistics, clear caches, reset the deployment map | – | – | ✓ |
| Refresh notification subscriptions, rotate the GraalVM engine | – | – | ✓ |

:::info
**Version history needs the create role.** Reading a mapping is open to everyone, but its drafts and published
versions are not. Those endpoints, and the version counts shown under **Monitoring → Versions**, require
`ROLE_DYNAMIC_MAPPER_CREATE`. A user without it sees the mapping but an empty version drawer.
:::

:::info
**A visible button does not guarantee permission.** The service checks the role for every action. For example, the
mapping page offers **Reset deployment cache** to users with the create role, but the service allows it only for
`ROLE_DYNAMIC_MAPPER_ADMIN` and answers with `403` otherwise.
:::

#### The HTTP connector role {#http-connector-role}

`ROLE_DYNAMIC_MAPPER_HTTP_CONNECTOR_CREATE` is separate from the other two and grants exactly one thing: posting
payloads to the [Default HTTP Connector](/c8y-pkg-dynamic-mapper/introduction/managing-connectors) at

```text
https://<YOUR_CUMULOCITY_TENANT>/service/dynamic-mapper-service/httpConnector/<MAPPING_TOPIC>
```

Assign it to the technical user your devices or upstream systems authenticate as. That user needs **no other
Dynamic Mapper role**. It is an ingest permission, not an editing one. Conversely, `ROLE_DYNAMIC_MAPPER_ADMIN`
does *not* imply it: an administrator without this role still gets `403` from the ingest endpoint.

A request without the role is rejected with:

```text
403 Authenticated user does not have the required role: ROLE_DYNAMIC_MAPPER_HTTP_CONNECTOR_CREATE
```

#### Where permissions are enforced

Permissions are enforced by the microservice, not only by the user interface. The UI additionally hides or
disables actions a user cannot perform, and the Home page warns on load when a role is missing, but removing that
UI restriction does not grant access, because every state-changing endpoint checks the role itself. Outbound pages
disappear for everyone when **Outbound mappings** is off in the service configuration.

:::caution
The roles above govern the Dynamic Mapper's own API. They do not restrict **what a mapping does once it runs**. An
active mapping writes to Cumulocity using the microservice's own permissions, on behalf of every message that
matches its topic. Treat the ability to create and activate mappings (`ROLE_DYNAMIC_MAPPER_CREATE`) as a
privileged capability.
:::
