# Concept review: gaps and proposed improvements

Review date: 2026-10-31. Basis: every page in `docs/feature/` was checked against the code, and the findings below come from that pass. All items were read from source; none were reproduced at runtime. Treat each as a hypothesis to confirm with a test before fixing.

Priority: **P1** loses or corrupts data, or breaks a documented guarantee. **P2** weakens reliability or security under realistic conditions. **P3** inconsistency or cleanup.

## 1. Cross-cutting themes

The individual findings share five root causes. Fixing the cause is cheaper than fixing each symptom.

| Theme | Where it shows | Proposed direction |
|---|---|---|
| **Acknowledgement is not tied to the pipeline result.** | AMQP 0-9-1 and 1.0 ack when `onMessage()` returns; the HTTP connector returns 200 on hand-over; Kafka commits past a failed record. MQTT, Pulsar, and Pub/Sub wait for the result. | Define one rule in `connector-framework.md`: a connector acknowledges only after `getProcessingResult()` completes, and the failure policy (retry, drop, dead-letter) is a per-connector property. Add a shared contract test. |
| **Failure outcomes are booked inconsistently.** | A cancelled leg counts as success and resets the failure streak. A null or empty result is a failure for Java extensions but "ignore" for Smart Functions. Publish failures do not extend the streak. Exact `==` threshold crossing never retriggers a failed deactivation. | Introduce an explicit outcome enum on the leg (`PROCESSED`, `IGNORED`, `CANCELLED`, `FAILED`) and have `ConsolidationProcessor` be the only place that maps outcomes to counters and streaks. Use `>=` with an "already deactivating" guard. |
| **Pipeline stages do not stop on error.** | No stop after failed deserialization or enrichment; `runSingleInboundMapping` can return `null`; ignore-checks are missing after `FlowResultInboundProcessor`. | The router checks `hasError()` after every stage and always returns a context. |
| **Fail-open defaults and loose input checks.** | `isMappingDeployed` returns true when the check throws. `resolveExternalId2GlobalId` treats any `SDKException` as "not found", so a 5xx can trigger device creation. JSON template validation accepts garbage. | Fail closed. Treat only 404 as not found. Use a strict JSON parse. |
| **Configuration drift between layers.** | Version retention defaults are 5 (constructor), 10 (UI), 10 (service fallback). Reconnect and backoff differ per connector. Two allow-list copies for GraalVM host classes. | One source of truth per setting. Move reconnect and backoff into a single `AConnectorClient` helper. |

## 2. Findings by area

### Data integrity (P1)

1. **Failed device lookup can create duplicate devices.** `C8YAgent.resolveExternalId2GlobalId` maps every `SDKException` to null. With `createNonExistingDevice` on, a timeout or 5xx during lookup leads to a create attempt. Treat only 404 as not found; retry or fail the message otherwise.
2. **Re-saving an unchanged type list deletes the type subscription.** `Utils.createChangedTypeFilter` returns `null` for unchanged types and `SubscriptionManager.updateSubscriptionByType` treats `null` as empty. Return early when nothing changed.
3. **Fixed 2026-10-31.** **Deleting the `SHARED` code template breaks engine creation.** The delete is allowed (`internal=false`) and `GraalVMContextService` dereferences `.get("SHARED")` without a null check. Refuse the delete, or fall back to an empty source.
4. **Fixed 2026-10-31.** **Connector import stores `****` as the real secret.** Create does not call `restoreMaskedSecrets`. Reject or clear masked values on import.
5. **Service-configuration import replaces `codeTemplates` unchecked** and does not reload `SHARED`. Reuse the checks of `PUT /code`.
6. **Java extension state never persists.** `setNativeState` is saved only in `clearState()`, which nothing calls, and outbound extensions get no `FlowStateStore`. Save after `onMessage`, or document it as unsupported and fix the sample that relies on it.
7. **Bulk measurement merge keeps only the first request's `sourceId` and `externalId`.** Group by source before merging.

### Reliability (P1/P2)

8. **Fixed 2026-10-31 for AMQP, HTTP, and Kafka.** **Early acknowledgement** in AMQP (both variants), HTTP, and Kafka commit ordering (see theme above). AMQP 0-9-1 also acks `autoAck=true` consumers and skips ack on redelivery. Fix both.
9. **Fixed 2026-10-31.** **Cancelled leg booked as success** in `AbstractFlowProcessor`, which resets the failure streak.
10. **Fixed 2026-10-31.** **Empty extension result counts as failure** and can auto-deactivate a mapping whose extension intentionally deduplicates.
11. **Operation auto-ack is optimistic.** `SendOutboundProcessor` reports SUCCESSFUL when the connector is disconnected or the leg was filtered. Report the real outcome.
12. **Pulsar `monitorSubscriptions()`** closes a briefly disconnected consumer and nothing resubscribes it. Let the client reconnect, or resubscribe.
13. **Pub/Sub:** several inbound topics share one subscription, so a message can arrive under the wrong topic. A failed publisher is never evicted and no `RetrySettings` are set.
14. **`AbstractMqttCallback`** neither acks nor reconnects on `ExecutionException`.
15. **`WebHookInternal` health check** presets a full URL that `healthUri()` rejects. Use a relative path.

### Security (P2)

16. **External Java extensions run unsandboxed** in the shared JVM and `extension.onMessage` cannot be cancelled by the pipeline timeout. The requirement "must not destabilize the service" is not enforceable. Options: run extensions with a watchdog thread and a hard budget, or restrict upload to the Admin role plus an explicit warning in the UI.
17. **Partly fixed 2026-10-31 (the two GET endpoints; the `OperationController` 422 handler is open).** **Missing authorization:** `GET /mapping/version-counts` and `GET /extension` have no role check. `OperationController` has no `MappingValidationException` handler, so activation validation failures probably do not return the documented 422.
18. **Reset deployment cache** wipes all deployments, is enabled in the UI for Create users, and returns 403 for them because `REQUIRED_ROLES` says Admin. Align the role, rename the action, and add a confirmation.
19. **Insecure TLS:** AMQP 1.0 with `trustCustomCertificate` sets `trustAll=true` and `verifyHost=false`. Build an `SSLContext` from the supplied certificate.
20. **`OutboundUrlValidator`** allows private ranges and resolves DNS once (rebinding). Pin the resolved address for the request and decide explicitly whether private ranges are allowed per deployment.
21. **Subscription request parameters:** `subscription` is a free string and type names are not escaped in the type filter. Whitelist names; reject `'`.
22. **`InboundMessageRouter.isMappingDeployed`** fails open (see theme).

### Operations and observability (P2/P3)

23. **Service logs:** the UI offers TRACE to ERROR but root is INFO, so a tenant choosing DEBUG gets nothing. Offer only deliverable levels, or let the appender capture below the logger level for opted-in tenants.
24. **Device relation edits reach MQTT Service only at minute 30 of each hour,** and several instances overwrite each other. Sync after each edit and serialize across instances.
25. **Explorer:** the dedup key is a 32-bit `Arrays.hashCode`, and there is no cap on sessions per tenant or user. Use a stronger hash and add a session cap.
26. **No `testConnection`** for MQTT, AMQP, and Pub/Sub, so the UI test button cannot cover three of nine connectors.
27. **Test gaps:** no tests for `HttpClient`, `KafkaClientV2` beyond restart, AMQP 1.0, `WebHookInternal`, or the MQTT 5 client. A green build does not prove these work.

### Consistency and cleanup (P3)

28. Version retention default: 10 everywhere.
29. `UI delete all relations` calls `DELETE /relation/relations`; the service exposes `DELETE /relation/client`.
30. `PUT /subscription` does not expand child devices; `POST` does.
31. `PUT /deployment/defined` documents a 404 the code never returns. GET endpoints on `/deployment` declare `consumes=json` and return 415 without the header.
32. The code-template tooltip says reset restores `SHARED`; the backend keeps it.
33. Dead code: `createDefaultAIAgents()`, `getAgentPrompts()`, `MappingType.CODE_BASED`, `TransformationType.SUBSTITUTION_AS_CODE`, unused `PUBLISHER_RETRY_OVERHEAD_SECONDS`. AI agent prompts exist twice (`prompts/*.txt` and the agent JSON) and can drift.
34. Stale comments: javadoc "30 s" timeout in `ProcessingResultHelper`, and a router comment claiming outbound filtering by subscription topic.
35. `updateExistingDevice` only labels the request method, and inverted. Remove it or implement it.
36. JSONata and JSON template validation: replace `JSONTokener.nextValue()` with a strict parse.

## 3. Concept-level gaps

These are not defects. They are capabilities a production integration platform usually needs and this one lacks.

| Gap | Why it matters | Proposal |
|---|---|---|
| **No dead-letter path.** A message that fails after retries is dropped or redelivered forever, depending on connector. | Operators cannot inspect or replay failures. | Per-mapping optional dead-letter target (a Cumulocity event or a broker topic) with the original payload and error; a replay action in the UI. |
| **No backpressure model.** Pipeline concurrency, queue depth, and per-tenant fairness are not specified. | One noisy tenant or mapping can starve others. | Document and enforce per-tenant concurrency and queue limits; expose queue depth as a metric. |
| **No uniform metrics contract.** Status counters exist per mapping, but connector lag, ack latency, and reconnect counts are not exposed consistently. | Cannot alert on a stalled connector. | Define a metric set in `connector-framework.md` and publish it through the existing OpenTelemetry support. |
| **Deployment is a manual mapping-to-connector table.** | "My mapping does nothing" is the common support case. | A diagnostic view that explains why a mapping is not running (not deployed, connector down, inactive, filter, validation) using the defined vs effective data already available. |
| **Planning documents conflict with the shipped design.** `REQUIREMENTS-VERSION-MAPPING.md` uses integer versions and oldest-first pruning; code uses semver labels and keeps the highest. FR-10 conflicts with deactivation behavior. | Readers cannot tell which is authoritative. | Mark the planning files as historical with a pointer to `mapping-versioning.md`. |
| **Single-instance assumptions.** Hourly relation sync, in-memory Smart Function state, and caches are per instance. | Scaling the microservice horizontally gives inconsistent behavior. | State the supported topology in `limitations.md`; for state that must be shared, move to a shared store or document the loss on restart. |

## 4. Suggested order of work

1. **Small and safe, do first:** items 2, 3, 4, 17, 21, 28, 29, 31. Each is a few lines with an obvious test.
2. **Behavior fixes with tests:** items 1, 8, 9, 10, 11, 18. Add the shared connector acknowledgement contract test before touching individual connectors.
3. **Design work:** the outcome enum (theme 2), dead-letter path, extension watchdog (item 16), and the "why is my mapping not running" diagnostic.
4. **Cleanup:** items 33 to 36 once the behavior changes have landed.

## 5. What was changed in the specs

- 25 existing feature pages were corrected against the code. Line-number anchors that had gone stale were removed.
- 7 pages were added: deployment map, outbound subscriptions, code templates, device isolation, import and export, mapping tree, roles and permissions. `feature/README.md` lists them.
- Planning documents were not edited.
- Nothing was committed.

## 6. Not verified

- Behavior on a deployed tenant (service logs, Notification 2.0 inventory cache freshness).
- Third-party documentation claims (Confluent, Aiven, MQTT Service keep-alive).
- Whether JSONata `DEFAULT` still runs in the inbound router.
- Whether `WebHookInternal` connects at runtime.
