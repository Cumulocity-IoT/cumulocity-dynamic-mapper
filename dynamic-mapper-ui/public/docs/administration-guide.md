---
title: Administrator guide
---

### Administrator guide {#administration-guide}

This guide is for the person who installs, configures, and operates the Dynamic Mapper for a tenant. It links to the
detailed pages for each topic. Use it as a checklist, from first installation to day-to-day operation.

#### Prerequisites {#admin-prerequisites}

- A Cumulocity tenant with the microservice feature subscribed. If it is not available, ask your Cumulocity contact or
  support.
- A user with administrator privileges in the tenant, to upload the microservice and the web application.
- Optional, for [AI-assisted mapping](/c8y-pkg-dynamic-mapper/introduction/ai-assisted): the AI Agent Manager
  microservice deployed in the tenant, with an AI provider and API key configured in the Agent Manager.
- Optional: a reachable message broker for each connector you plan to use. See
  [Managing connectors](/c8y-pkg-dynamic-mapper/introduction/managing-connectors).

The Dynamic Mapper needs a Cumulocity platform version that supports the features you enable. For example,
**External ID binding during MO creation** needs a platform of May 2026 or later. The full list of supported platform
versions is not yet documented here. <!-- TODO(owner): add the supported platform version matrix. -->

#### Install the components {#admin-install}

Two components make up the Dynamic Mapper. Both are published as binaries on the
[releases page](https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper/releases).

| Component | File | Where to upload it |
|---|---|---|
| Microservice | `dynamic-mapper-service-*.zip` | **Administration → Ecosystem → Microservices → Add microservice** |
| Web application | `dynamic-mapper-ui.zip`, or the community store entry | **Administration → Ecosystem → Packages**, or **Extensions → dynamic-mapper** |

**Microservice.** Drop the ZIP on the **Add microservice** dialog and subscribe the microservice to your tenant when
prompted. The microservice is a `*.zip`. A processor extension is a `*.jar`.

**Choose a size.** The microservice comes in four variants. Pick the one that matches your peak message rate and
replace it later if the load changes.

| Variant | CPU | Memory | Suitable for |
|---|---|---|---|
| Starter | 0.5 | 2 GB | Up to 625 messages per second |
| Standard | 2 | 4 GB | Up to 2,500 messages per second |
| Performance | 8 | 8 GB | Up to 10,000 messages per second |
| Max | 16 | 16 GB | Up to 20,000 messages per second |

**Web application.** The community store offers it as a blueprint application or as a plugin. For a blueprint, open
**Administration → Ecosystem → Extensions → dynamic-mapper**, select **Deploy application**, enter an application key
and a path, and select **Deploy**. The application then appears in the application switcher. To upload it yourself,
open **Administration → Ecosystem → Packages**, select **Add application**, and choose `dynamic-mapper-ui.zip`.

If the navigation stays empty or the application shows no data, check that the `dynamic-mapper-service` microservice is
subscribed. The application shows a warning when it is not.

#### Assign roles {#admin-roles}

Three roles control access. Assign them in **Administration → Role Management**, to a global role or to a user group.

| Role | Use it for |
|---|---|
| `ROLE_DYNAMIC_MAPPER_CREATE` | People who build mappings and subscriptions. |
| `ROLE_DYNAMIC_MAPPER_ADMIN` | People who manage connectors, service configuration, code templates, extensions, service logs, and maintenance operations. Implies the create role. |
| `ROLE_DYNAMIC_MAPPER_HTTP_CONNECTOR_CREATE` | Devices or systems that post payloads to the Default HTTP Connector endpoint. Independent of the other two. |

Assign the smallest role that does the job. See [Managing permissions](/c8y-pkg-dynamic-mapper/introduction/access-control)
for the full list of what each role can do.

#### First-time configuration {#admin-first-setup}

Work through these steps in order after the first installation.

1. **Review the service configuration.** Open **Configuration → Service configuration**. Check these settings first:
    - **Outbound mappings**: leave it on if you need the Cumulocity to broker direction. Changing it later requires you
      to unsubscribe and subscribe the microservice again.
    - **External ID binding during MO creation**: turn it off on platforms older than May 2026, such as Cumulocity Edge.
    - **Maximum processing attempts per message** and the Smart Function time limits: the defaults suit most tenants. See
      [Reliability settings](/c8y-pkg-dynamic-mapper/introduction/reliability-settings).

   All settings are described in [Service configuration](/c8y-pkg-dynamic-mapper/introduction/service-configuration).
2. **Create and enable a connector.** Add a connector under **Configuration → Connector configuration**, enter the
   broker details, and enable it. Use the connector page for your broker, for example the
   [MQTT connector](/c8y-pkg-dynamic-mapper/introduction/connector-mqtt). Turn on **Send connector lifecycle** in the
   service configuration to see connector status events on the connector **Details & logs** page.
3. **Choose the monitoring you need.** Leave **Send mapping statistics** on so that **Monitoring → Statistic processed**
   has data. Turn the other event settings on only while you need them. Events count against the storage of your tenant.
4. **Set up AI generation, if you use it.** Under **Service configuration → AI Agent**, select the **JSONata AI Agent**
   and the **SmartFunction AI Agent**. Both agents must exist in the AI Agent Manager. Mapping creation works without
   AI.
5. **Load sample mappings, if you want a starting point.** On the mapping page, open the **⋮** menu and select **Add
   sample mappings**. See [Defining mappings](/c8y-pkg-dynamic-mapper/introduction/define-mapping).

#### Operate the service {#admin-operate}

**Check health.** Open **Monitoring**. The statistic pages show messages processed, errors, and the last error per
mapping. The connector page shows whether each connector is connected. See
[Monitoring](/c8y-pkg-dynamic-mapper/introduction/monitoring).

**Read the logs.** The **Service logs** view in **Monitoring** shows the log lines of your tenant. It can filter by
mapping. Only users with `ROLE_DYNAMIC_MAPPER_ADMIN` can open it. Change what is stored, the level, and the retention under
the service log settings: [Service log settings](/c8y-pkg-dynamic-mapper/introduction/monitoring#service-logs-settings).
For problems that do not appear there, see
[Reading the microservice log](/c8y-pkg-dynamic-mapper/introduction/troubleshooting#microservice-log).

**Maintenance actions.** These buttons act immediately and need `ROLE_DYNAMIC_MAPPER_ADMIN`:

| Action | Where | When to use it |
|---|---|---|
| **Clear inbound external ID cache**, **Clear outbound external ID cache**, **Clear inventory cache** | Service configuration, **Caching** tab | After you change a cache size, or when devices resolve to stale data. |
| **Clear flow state** | Service configuration, **Caching** tab | To reset the state kept by Smart Function and Java Extension mappings. |
| **Rotate GraalVM Engine** | Service configuration, **Processing** tab | To release memory held by the JavaScript engine. |
| **Reload mappings in service**, **Reset deployment cache** | **⋮** menu on the mapping page | After a change that the service did not pick up. |
| **Init internal code templates** | **⋮** menu on the code templates page | After an upgrade, to restore system templates to their defaults. Your own templates are not changed. |

**Handle a broker outage.** Inbound messages are redelivered by the broker where the connector supports it. Outbound
messages are held while a broker is down and sent when it returns. The behavior differs per connector. See
[Reliability settings](/c8y-pkg-dynamic-mapper/introduction/reliability-settings#qos-by-connector).

#### Back up and move a configuration {#admin-backup}

The Dynamic Mapper keeps its data in your tenant. Export what you need to rebuild or copy a setup:

- **Service configuration:** **Export** on the service configuration page. **Import** restores it on another tenant.
- **Mappings:** **Export all mappings** in the **⋮** menu of the mapping page. **Import mappings** loads them again.
- **Code templates and processor extensions:** keep the source of your custom templates and extension JAR files in
  version control.

Connector configurations are not part of these exports. Record them separately, and keep credentials in your own secret
store. <!-- TODO(owner): confirm whether connector configuration export is available and document it. -->

#### Upgrade {#admin-upgrade}

1. Export the service configuration and all mappings, as described above.
2. Upload the new `dynamic-mapper-service-*.zip` as a new version of the microservice, and update the web application.
3. After the upgrade, open the code templates page and select **Init internal code templates**. Tenants keep the system
   templates they had stored, so new defaults appear only after this step.
4. Open **Monitoring** and check that the connectors reconnect and that the error counts stay at zero.

Setting changes that need a new subscription, such as **Outbound mappings** and **Device Isolation Cumulocity MQTT
Service**, take effect only after you unsubscribe and subscribe the microservice again.
<!-- TODO(owner): document the tested upgrade paths and the rollback procedure. -->

#### Observability for operators {#admin-observability}

The microservice exports traces, metrics, and logs through OTLP/HTTP to the collector at
`http://cumulocity-grafana-alloy:4318` by default. At startup, the service checks once whether the collector is
reachable. If it is not, the service turns the export off for that run and starts normally. If the collector becomes
reachable later, export stays off until the service restarts.

| Variable | Effect |
|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Sends telemetry to a different collector. |
| `OTEL_SDK_DISABLED=false` | Forces export on even when the collector is unreachable at startup. Use it to debug the collector. |

#### Where to go next {#admin-next}

| Task | Page |
|---|---|
| Find the cause of a failure | [Troubleshooting](/c8y-pkg-dynamic-mapper/introduction/troubleshooting) |
| Tune retries, timeouts, and QoS | [Reliability settings](/c8y-pkg-dynamic-mapper/introduction/reliability-settings) |
| Understand every setting | [Service configuration](/c8y-pkg-dynamic-mapper/introduction/service-configuration) |
| Check who can do what | [Managing permissions](/c8y-pkg-dynamic-mapper/introduction/access-control) |
| Add a Java processor extension | [Java extensions](/c8y-pkg-dynamic-mapper/introduction/javaextension) |
