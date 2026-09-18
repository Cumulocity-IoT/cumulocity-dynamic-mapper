---
title: Troubleshooting
---

### Troubleshooting {#troubleshooting}

The following lists common problems and how to resolve them.

#### Messages arrive but no Cumulocity objects are created

- Check that the mapping is **enabled** (activated). A greyed-out mapping icon means it is disabled.
- Verify the **topic pattern** matches the topic your device is publishing to. Wildcards `#` and `+` follow MQTT
  semantics.
- Open [**Monitoring → Statistics**](/c8y-pkg-dynamic-mapper/node2/monitoring/statistic/inbound) and check
  whether the message counter for the mapping increases. If it does not, the topic pattern is not matching.
- Check the **Execution Filter** (**Filter execution mapping** on the *Select templates* step) — if set, it must
  evaluate to `true` for the incoming payload, otherwise the message is skipped without an error.
- If the counter increases but objects are not created, check the **Event Log** for transformation errors.

#### Transformation errors in the Event Log

- **"Device not found"** — the `externalId` returned by your mapping does not match any registered device.
  Enable **Create non-existing devices** on the mapping or pre-register the device in Cumulocity.
- **"Cannot evaluate expression"** (JSONata) — the JSONata expression references a field that is missing or null
  in the source payload. Add a null-guard, e.g. `payload.value ? payload.value : 0`.
- **Smart Function throws a JavaScript exception** — the full stack trace is shown in the Event Log. Use
  `console.log()` in your function to emit diagnostic output visible in the Event Log.
- **"Fragment not found in cache"** — the managed object fragment accessed via `context.getManagedObject()` is
  not in the [**Fragments from inventory to cache**](/c8y-pkg-dynamic-mapper/node3/serviceConfiguration/general)
  list. Add the exact fragment name or a matching glob pattern (e.g. `sparkPlugB_DBIRTH_*`) and clear the
  inventory cache or wait for it to refresh.

#### Outbound mapping does not trigger

- Check the mapping's **Source API** — it is used as a filter to select which mappings apply when a
  notification arrives. If it does not match the API of the incoming notification (e.g. **Measurement** vs.
  **Event**), the mapping is silently ignored for that notification.
- Verify that a **subscription** exists for the device. Without a subscription, no outbound messages are
  processed regardless of mapping state.
- Check the **Inventory Filter** — if set, it must evaluate to `true` for the device's managed object.
- Check the **Execution Filter** — if set, it must evaluate to `true` for the triggering payload.
- Confirm the mapping's **connector** is connected (green status in
  [**Monitoring → Statistics**](/c8y-pkg-dynamic-mapper/node2/monitoring/statistic/inbound)).
- If the mapping has **useExternalId** enabled and reads `context.getConfig().externalId` in its
  Smart Function/Flow code, a stale entry in the **Outbound ID Cache** (e.g. after re-enrolling the device under
  a different external ID) can route the message using the old external ID. Clear it under
  [**Monitoring → Cache statistic**](/c8y-pkg-dynamic-mapper/node2/monitoring/cache) using
  **Clear outbound external ID cache**.

#### Smart Function returns stale device inventory data

- The mapper caches inventory fragments for performance. Navigate to
  [**Service Configuration → Caching**](/c8y-pkg-dynamic-mapper/node3/serviceConfiguration/general) and click
  **Clear inventory cache** to force a refresh.
- Ensure the required fragment is listed in
  [**Service Configuration → Function → Fragments from inventory to cache**](/c8y-pkg-dynamic-mapper/node3/serviceConfiguration/general).
  Only listed fragments (or fragments matching a configured glob pattern, e.g. `sparkPlugB_DBIRTH_*`) are loaded
  into the cache.

#### Connector disconnects repeatedly

- Check the broker's access logs for authentication or authorization errors.
- Verify the connector credentials, host, and port in the connector configuration.
- For TLS connections, ensure the broker's certificate is trusted and the CA certificate is correctly configured
  in the connector.
- Check the Event Log for TLS handshake or connection timeout errors.

#### Enabling debug mode for a mapping

Debug mode logs detailed processing information for a specific mapping without enabling verbose logging globally.
In the mapping list, use the context menu (three-dot icon) to enable **Debug** for a mapping. Debug output
appears in the **Event Log** and in the microservice logs. Disable debug mode after troubleshooting to avoid
excessive log volume.

:::caution
Debug mode logs full payload content. Do not leave it enabled in production if payloads contain personally
identifiable information (PII) or secrets.
:::
