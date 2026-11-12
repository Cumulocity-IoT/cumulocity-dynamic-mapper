# Message de-duplication (issue #692)

Status: approach B (`$isDuplicate` in the mapping filter), phase 1 (backend) implemented on branch
`feature/deduplication-message`. The mapping model is unchanged. Approach A (Smart Function pattern) and phases
2 to 4 are open.

## Problem

Brokers and gateways sometimes deliver a message twice, for example after a reconnect or an MQTT QoS 1 retry.
Each copy creates another measurement, event, or alarm in Cumulocity. The Dynamic Mapper has no built-in check
for this.

## Scope

- Inbound mappings only.
- Drops exact repeats inside a window of 1 second to 60 minutes.
- Memory only. Fingerprints do not survive a restart and are not shared between service instances.
- Not in scope: windows of days or weeks, and near duplicates such as the same timestamp with a changed value.

## Two approaches

### A. Smart Function pattern, state only

The script builds a key per message, for example `deviceId|time`. It reads `context.getState("dup:" + key)`. If
the stored timestamp is inside the window, the script returns no output. Otherwise it calls
`context.setState("dup:" + key, now)` and processes the message. Use one state key per fingerprint. One object
holding all fingerprints would lose updates when messages run concurrently. The template
`template-SMART-INBOUND-04.js` already uses state to suppress consecutive duplicate errors.

### B. `$isDuplicate(key, windowSeconds)` in the mapping filter (implemented)

A JSONata function, available in `filterMapping`, backed by a dedicated in-memory store. No new mapping fields:
the rule lives in the existing filter, which is already saved, exported, imported, and edited in the UI.

```
$not($isDuplicate(deviceId & "|" & time, 60))
```

The filter must be true for a message to pass. Combine it with other conditions:
`value > 10 and $not($isDuplicate(deviceId, 60))`.

## Comparison and gaps

| Requirement | A. Smart Function, state only | B. `$isDuplicate` in the filter |
|---|---|---|
| Drop exact repeats | Yes, with a key chosen by the author. | Yes, with a key chosen by the author. |
| No change to the mapping model | Yes. | Yes. |
| Atomic under concurrent messages | **Gap.** State is copied at message start and committed at message end, so two copies running at the same time can both read "not seen" and both pass. | Yes. One atomic `compute` on a concurrent map. |
| Expiry of fingerprints | **Gap.** No per-key TTL. The script must prune its own keys. The only automatic cleanup is `flowStateRetention` (default 1440 minutes), which acts on the whole mapping state. | Yes. Entries expire at the end of the window. A job removes them every minute. |
| Memory bound | **Gap.** State has no size limit (finding 5 in `docs/improvements/flow-state-review.md`). | Yes. 100,000 fingerprints per mapping, then fail open. |
| Cost per message | **Gap.** Each message copies the whole state map, so cost grows with the number of fingerprints held. About 1,500 keys for the customer case, which is acceptable. | Constant. One map lookup. |
| Drop counter and log line | **Gap.** Only what the script writes itself. | Per-mapping counter and a debug log line. A drop shows as a filter mismatch in the status. |
| Works for every transformation type | **Gap.** Smart Function mappings only. | Yes. The filter runs before the transformation. |
| Hash of the whole payload | **Open question.** Not verified that the script sandbox allows Java classes. | `$string($)` as the key covers exact repeats. Object key order may differ between copies. |
| Custom rules, for example per-device windows | Yes. Any logic the script can express. | Yes for key and window. No custom logic beyond JSONata. |
| Rule next to the mapping logic | Yes. The rule is in the script. | Yes. The rule is in the filter. |
| Outbound mappings | Not applicable. | **Gap.** Not supported. The function is a no-op with a warning, so it cannot drop every message. |
| Failed processing after the filter | Script decides. | **Gap.** A message that passes the filter and then fails later is already recorded, so its retry is dropped. |

### Closing the gaps

| Gap | Approach | Closing step |
|---|---|---|
| A is not atomic, has no TTL or size bound, no counter | A | Phase 3 adds `context.isDuplicate(key, windowSeconds)` on the store from B. |
| A only covers Smart Function mappings | A | Not closable. Use B for JSONata and Java extension mappings. |
| B has no outbound support | B | Out of scope. Decide later if outbound brokers need it. |
| B records before the transformation succeeds | B | Document it. Option for later: record after a successful send. |
| B counts drops as filter mismatches | B | Phase 2 shows a separate drop counter. |

The sample template (phase 1b) is a stopgap for approach A. Until `context.isDuplicate` exists, it carries the
concurrency and expiry gaps listed above, and the template header says so.

## Design of approach B

### Key

The key is a JSONata expression evaluated against the payload.

- Strings, numbers, and booleans are used as they are. Objects and arrays are serialized to JSON.
- The final text is hashed with SHA-256, so long keys use no extra memory.
- The author adds a separator between fields, for example `"|"`, so `a` + `bc` differs from `ab` + `c`.
- The store is per tenant and mapping, so neither is part of the key.
- The topic is not part of the key. Use `_TOPIC_LEVEL_` in the key if topics matter. Not yet verified that the
  filter can see it.
- If the key has no value, or the window is not a number from 1 to 3600, the function returns `false`, logs a
  warning, and the message is processed. The check fails open so that a bad expression never drops data.

### Store

`MessageDeduplicationService` holds `tenant -> mappingIdentifier -> fingerprint -> expiry`.

- `isDuplicate` uses one atomic `ConcurrentHashMap.compute`, so two concurrent copies cannot both pass.
- The window starts at the first copy and does not slide. A third copy after the window expires is processed.
- Each mapping holds at most 100,000 fingerprints. When full, expired entries are removed first. If it is still
  full, the message is processed and not recorded (fail open).
- A scheduled job in `CacheRetentionService` removes expired entries and empty mapping maps every minute. Tenant
  removal clears the tenant.
- This is a separate store from `FlowStateStore`, for the atomicity reason in the comparison above.

### Where the filter is evaluated

| Place | Behavior |
|---|---|
| `FilterInboundProcessor` (inbound runtime) | Registers `$isDuplicate`. The only place that records fingerprints. |
| `MappingResolverService.evaluateMessageFilter` (outbound runtime) | Registers a no-op that returns `false` and logs a warning. Without it the unknown function would fail the evaluation, and a failed filter drops every message. |
| UI mapping editor, in the browser (`MappingService.compileExpression`) | Registers a stub that returns `false`. Without it the editor marks the filter invalid and blocks saving. Checked with the jsonata library: without the stub the error is "Attempted to invoke a non-function". |
| Dry Run and Live Test | Go through `FilterInboundProcessor`. The function never records during a test run. |
| `MappingTestService` | Only builds a warning text. No evaluation. |

## Phases

1. **Backend, approach B (done):** store, `$isDuplicate` in the inbound filter, outbound no-op, browser stub,
   cleanup, unit tests.
   - **1b. Sample template (open):** `SMART-INBOUND-xx` with the script pattern, a window, and pruning of old keys.
     The header lists the gaps above.
2. **Visibility and UI:** drop counter in the mapping status. Editor hint and an example for `$isDuplicate`. UI
   tests for the stub.
3. **Smart Function access:** `context.isDuplicate(key, windowSeconds)` on the same store. Both entry points share
   one store per mapping, so a key used in both places counts once.
4. **Docs:** user guide section that says when to use the filter function, the script pattern, or
   `context.isDuplicate`. Mention the retry-after-failure caveat.

## Open points

- The drop counter is in memory. Decide whether it belongs in the persisted mapping status.
- Whether to add outbound support. The issue only describes inbound brokers.
- Whether the filter can see `_TOPIC_LEVEL_`, and whether the script sandbox can hash a payload.
- The customer case (25 devices, about 40 duplicates a week) is covered by either approach. Pick the default
  recommendation for the user guide once phase 3 is decided.
