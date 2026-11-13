# Message de-duplication (issue #692)

Status: all phases implemented on branch `feature/deduplication-message`. The mapping model is unchanged.

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
$exists(deviceId) and $exists(time) and $not($isDuplicate(deviceId & "|" & time, 60))
```

The filter must be true for a message to pass. Combine it with other conditions:
`value > 10 and $not($isDuplicate(deviceId, 60))`. The key is recorded when the function runs, and given back if the
message does not end as delivered (see "Failed processing" below), so the order of the conditions does not matter. Put
`$isDuplicate` last anyway: JSONata stops at the first false operand of `and`, so the lookup is skipped for messages
that another condition rejects.

One rule for authors:

- **Missing fields.** `&` turns a missing field into empty text, so `deviceId & "|" & time` without `time` is
  `"d1|"`, a normal key, not "no value". The fail-open rule below never applies to it, and all messages of that device
  without `time` count as repeats of the first. Guard the fields with `$exists`. In a Smart Function, `+` gives the
  text `undefined` the same way.

## Comparison and gaps

| Requirement | A. Smart Function, state only | B. `$isDuplicate` in the filter |
|---|---|---|
| Drop exact repeats | Yes, with a key chosen by the author. | Yes, with a key chosen by the author. |
| No change to the mapping model | Yes. | Yes. |
| Atomic under concurrent messages | **Gap.** State is copied at message start and committed at message end, so two copies running at the same time can both read "not seen" and both pass. | Yes. One atomic `compute` on a concurrent map. |
| Expiry of fingerprints | **Gap.** No per-key TTL. The script must prune its own keys. The only automatic cleanup is `flowStateRetention` (default 1440 minutes), which acts on the whole mapping state. | Yes. Entries expire at the end of the window. A job removes them every minute. |
| Memory bound | **Gap.** State has no size limit (finding 5 in `docs/improvements/flow-state-review.md`). | Yes. 100,000 fingerprints per mapping, then fail open. |
| Cost per message | **Gap.** Each message copies the whole state map, so cost grows with the number of fingerprints held. About 1,500 keys for the customer case, which is acceptable. | Constant. One map lookup. |
| Drop counter and log line | **Gap.** Only what the script writes itself. | **Repeated** column in the mapping statistics (a part of **Filtered**), and a debug log line. |
| Works for every transformation type | **Gap.** Smart Function mappings only. | Yes. The filter runs before the transformation. |
| Hash of the whole payload | **Open question.** Not verified that the script sandbox allows Java classes. | `$string($)` as the key covers exact repeats. Object key order may differ between copies. |
| Custom rules, for example per-device windows | Yes. Any logic the script can express. | Yes for key and window. No custom logic beyond JSONata. |
| Rule next to the mapping logic | Yes. The rule is in the script. | Yes. The rule is in the filter. |
| Outbound mappings | Not applicable. | **Gap.** Not supported. The function is a no-op with a warning, so it cannot drop every message. |
| Failed processing after the filter | Script decides. | Yes. The key is given back if the message fails or ends without delivering, so the retry passes. |

### Closing the gaps

| Gap | Approach | Closing step |
|---|---|---|
| A is not atomic, has no TTL or size bound, no counter | A | Phase 3 (done): `context.isDuplicate(key, windowSeconds)` on the store from B. |
| A only covers Smart Function mappings | A | Not closable. Use B for JSONata and Java extension mappings. |
| B has no outbound support | B | Out of scope. Decide later if outbound brokers need it. |
| B records before the transformation succeeds | B | Done (phase 7): the claim is released on failure. |

Approach A with state only stays possible, but the template now uses `context.isDuplicate`, which closes the
concurrency, expiry, memory, and counter gaps for Smart Function mappings.

## Design of approach B

### Key

The key is a JSONata expression evaluated against the payload.

- Strings are used as they are. `MessageDeduplicationService.keyText` turns every other value into text, and both
  the filter function and `context.isDuplicate` call it. A whole number has no decimal point (`5.0` is `5`), and an
  object is written as JSON with its members sorted by name, so a copy that lists members in another order still
  matches. Objects and arrays can therefore be used as a key in both places.
- The text is hashed with SHA-256 and the first 128 bits are kept, as 32 hex characters. Long keys use no extra
  memory, and a collision is practically impossible.
- The author adds a separator between fields, for example `"|"`, so `a` + `bc` differs from `ab` + `c`.
- The store is per tenant and mapping, so neither is part of the key.
- The topic is not part of the key. Use `_TOPIC_LEVEL_` in the key if topics matter. Verified in code: enrichment
  runs before the filter and adds `_TOPIC_LEVEL_` to a JSON object payload. It does not for Smart Function
  mappings or for payloads that are not a JSON object.
- If the key has no value, or the window is not a whole number from 1 to 3600, the function returns `false`, logs a
  warning, and the message is processed. A window above 3600 is rejected, not shortened to 3600: a silent change would
  hide a configuration error. The check fails open so that a bad expression never drops data. A key built with `&`
  from a missing field has a value (see "Missing fields" above), so it is not covered by this rule.

### Store

`MessageDeduplicationService` holds `tenant -> mappingIdentifier -> fingerprint -> expiry`.

- `isDuplicate` uses one atomic `ConcurrentHashMap.compute`, so two concurrent copies cannot both pass.
- The window starts at the first copy and does not slide. A third copy after the window expires is processed.
- Each mapping holds at most 100,000 fingerprints. When full, expired entries are removed first, at most once per
  second per mapping, so a flood does not cost a scan per message. If it is still full, the message is processed and
  not recorded (fail open). A full mapping logs one warning per minute with the number of messages that passed
  unchecked since the previous warning.
- All mappings of all tenants together hold at most 1,000,000 fingerprints (about 150 MB), so many de-duplicating
  mappings cannot add up to an out-of-memory error. The total is approximate: it counts new keys and is recounted
  from the real sizes at most once per second, which also drops expired keys. Past the limit a new key is processed
  without a check and one warning per minute names the number of unchecked messages. Keys already stored still match.
- If the cleanup job removes the mapping's map, or a mapping save clears it, while a message is being checked, the
  check looks the map up again and writes into the current one.
- `check` returns a `Claim` for a message that was not a repeat. `ProcessingContext` keeps the claims, and
  `ConsolidationProcessor`, the last step of every leg, calls `releaseDeduplicationClaims()` when the message has an
  error, has a failed request, or was ignored for a reason other than being a repeat (another filter condition, the
  inventory filter, an empty Smart Function result). `release` removes the fingerprint only if it still holds that
  claim's expiry, so it cannot undo a newer recording. This closes the claim-then-fail gap without a second phase:
  the atomic claim at check time stays, which keeps concurrent copies from both passing. A copy that arrived while the
  first was still in flight has already been dropped and is not brought back.
- A scheduled job in `CacheRetentionService` removes expired entries and empty mapping maps every minute. Tenant
  removal clears the tenant.
- `MappingService` calls `clearMapping` when a mapping is deleted, and when an update changes the execution filter,
  the code, or the transformation type (`changesDeduplicationKeys`). Those fields give the recorded keys their meaning,
  and a deleted mapping that is imported again keeps its identifier. Without the call, old keys would drop messages of
  the new definition for up to 60 minutes. A save that changes none of them, such as a rename or an activation, keeps
  the keys, so a repeat that arrives right after it is still dropped. A previous mapping that cannot be read counts
  as changed.
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

1. **Backend, approach B (done):** store, `$isDuplicate` in the inbound filter, outbound no-op, browser stub with
   a UI test, cleanup, unit tests, and the user guide section `Dropping repeated messages` in
   `dynamic-mapper-ui/public/docs/define-mapping.md`.
2. **Visibility and UI (done):** `messagesDuplicate` in the mapping status (a subset of `messagesFiltered`), set
   through a flag on the processing context and booked in `ConsolidationProcessor`. **Repeated** column in the
   monitoring table, editor hint for inbound mappings, German translations, monitoring guide row. The status counter
   is persisted with the status and is the only counter; the store keeps none.
3. **Smart Function access (done):** `context.isDuplicate(key, windowSeconds)` on the same store, passed to
   `SmartFunctionContext` through its constructor so a script cannot replace it. Mirrored into the TypeScript types
   (interfaces and both mocks), the generated editor table, `smartfunction.md`, the AI prompt, and the contract
   test's canonical list. Sample template `template-SMART-INBOUND-14.js`. Both entry points hash the key the same
   way, so a key used in both places counts once. The editor offers `$isDuplicate` only in the inbound execution
   filter field (`props.mapperFunctions`).
4. **Docs (done):** `define-mapping.md` (filter function), `smartfunction.md` (`context.isDuplicate`),
   `monitoring.md` (Repeated). The missing-field case is documented.
5. **Review fixes (done):** window outside 1 to 3600 fails open in all entry points (no clamping), the store is
   cleared when a mapping is saved or deleted, and the doc and sample filter guard missing fields. Tests: window
   above 3600, `clearMapping`, missing field, condition order.
6. **Review fixes, second round (done):** one key normalizer for the filter and for Smart Functions (whole numbers,
   sorted object members), a second lookup after the write to close the cleanup race, a throttled scan when a mapping
   is full, 128-bit fingerprints, and the unused `fingerprint(topic, payload)` overload removed. The outbound stub
   logs once per mapping. Checked and not a bug: a Smart Function that calls `context.isDuplicate`, gets `true`, and
   returns `[]` is booked under Repeated, because an empty result sets `ignoreFurtherProcessing` and the call set
   `droppedAsDuplicate`.
7. **Retry after failure, and wrap-up (done):** claims are released when a message fails or ends without delivering
   (`Claim`, `Result`, `ProcessingContext.releaseDeduplicationClaims`, `ConsolidationProcessor`). The condition order no
   longer changes what is dropped. The user guide recommends the filter for JSONata and Java mappings and
   `context.isDuplicate` for Smart Functions. The TypeScript mock uses the same key text as the runtime.

Tests: unit tests per class, and `MessageDeduplicationIntegrationTest`, which runs the real `InboundMessageRouter`
(deserialization, enrichment, filter, JSONata extraction, substitution, consolidation) with a real store and real
mapping statuses. Only the send step is replaced, to count sends and to fail one. It covers a repeat, a different
key, the `$exists` guard, the retry after a failed send, two mappings on one topic, test runs, and 32 concurrent
copies.


- The **Repeated** counter is not in the monitoring chart, which splits Processed, Filtered, and Errors.
- Outbound support is out of scope (decided). The issue only describes inbound brokers.
- The customer case (25 devices, about 40 duplicates a week) is covered by either approach. Phase 3 is decided:
  recommend the filter function for JSONata and Java mappings, and `context.isDuplicate` for Smart Functions. The
  user guide says so (phase 7).
- Known limits: a full mapping stops recording new keys and logs a warning, and the
  inventory filter of an outbound mapping is a separate expression that does not offer `$isDuplicate`. One race
  remains, between the cleanup job removing an empty mapping map and a first key written at the same moment. A
  second lookup after the write closes almost all of it, the rest fails open.
