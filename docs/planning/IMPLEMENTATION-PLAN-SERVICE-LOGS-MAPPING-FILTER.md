# Implementation plan: filter Service logs by mapping

Status: **planned, not started.** The platform gate is passed (2026-10-17) — see [Gate: fragmentType query](#gate-fragmenttype-query-verified-on-the-platform). Option B is confirmed; the Java-SDK chaining check remains.

## Goal

On the *Service logs* page (Monitoring), let the user show only the log lines that were written while **processing a payload for one specific mapping** (inbound or outbound). Connector lines (connect, subscribe, reconnect, cache rebuild) are out of scope; they stay unfiltered/untagged.

## Decisions

| Question | Decision | Why |
|---|---|---|
| Which id? | `mapping.identifier` | Generated, stable, already the key for `MappingStatus`, flow state, GraalVM pool keys and deployment. `mapping.id` is the managed-object id (placeholder `"id"` by default) and is not guaranteed stable across export/import. |
| What does the UI show? | Mapping **name** in the dropdown, sends the **identifier** | The identifier is cryptic (`l19zjk`); names are mutable and not unique, so they are no key. |
| Where is the tag set? | Per-mapping task in the routers | Each mapping already runs as its own task on a virtual thread; the tag therefore covers JSONata, Smart Function, extensions and the C8Y REST calls on that thread, and nothing from the connector/dispatcher. |
| How is the filter applied? | **Option B (hybrid)**: chunk event fragment selects chunks server-side, a text tag selects records inside them | Avoids the paging problems of filtering after fetch (below). |
| Native log (owner tenant)? | Client-side filter of the loaded window only, labelled as such | The platform log is opaque text; no server help possible. |

## Why paging is the risk

Today a stored chunk is one platform event holding a time-contiguous run of **all** of the tenant's records. The cursor is a chunk's `dateTo` (exclusive bounds, a page closes only between two different end times). A window is `maxChunks` chunks (default 10); one request scans at most `MAX_PAGES × PAGE_SIZE = 10 × 50 = 500` events, each with full log text. `Page.dateFrom/dateTo/before` derive from the chunks **returned**.

Filtering after fetching (Option A) breaks that:

1. **Cursor ≠ what is shown.** A chunk dropped for having no match must still advance the cursor, otherwise Next re-scans the same 500 chunks. The reader needs *scannedUntil* separate from the displayed range, and the same-millisecond guard must compare against the last *scanned* chunk, not the last output chunk.
2. **Empty page with `more = true`.** A rare mapping can match nothing in the newest 500 chunks. `read()` then returns `dateFrom=null, dateTo=null` — no cursor. The UI makes it worse: `!window.logs && !window.empty` shows "No logs available" and returns **without updating the window**, so the buttons keep acting on the old unfiltered window.
3. **Cost.** 500 full-text events fetched and mostly discarded per request; auto refresh (`fetch({})` every 10 s) repeats this per open tab.
4. **Record level.** `ServiceLogChunk.records` is not stored, so dropping non-matching records inside a chunk needs the text re-split into records (multi-line records; a logged payload can contain a line that looks like a header). `lines`/`omitted` no longer describe what is shown.
5. **Two sources**, one UI (native log can only be filtered client-side).
6. Existing weaknesses (chunks from several pods interleave; a late-written chunk with an older `dateTo` is skipped by "next") become more visible on long walks.

Option B removes 1–3 and shrinks 4: the platform returns **only chunks that contain the mapping**, so a page is N matching chunks and the cursor is the last matching chunk's `dateTo`, exactly as today.

## Design

### 1. Tag at the processing boundary
- New `dynamic.mapper.util.MappingLogContext` — AutoCloseable MDC scope, same shape as `TenantLogContext`, own key `mapperMapping` (own key so it can never collide with SDK keys).
- Open it in:
  - `InboundMessageRouter.processSingleInboundMapping` (`processor/inbound/InboundMessageRouter.java`)
  - `OutboundMessageRouter.processSingleOutboundMapping` (`processor/outbound/OutboundMessageRouter.java`)
- Do **not** propagate it through `MdcPropagatingExecutorService` (that carries only the tenant on purpose); set it inside the per-mapping task.
- Not tagged, by design: dispatcher lines (topic resolution, "no mapping"), connector lines.
- Known gaps: callbacks on foreign threads (SDK/HTTP client) lose the tag unless wrapped; the 422 resend path in `InboundMessageDispatcher` re-enters the router and is tagged by the same call site, but lines the dispatcher itself logs around the resend are not.

### 2. Tag in the text
- Print `[m:<identifier>]` in the log line **only when set**, at a fixed position (after the logger, before ` : `). Applies to the console pattern (`application.properties`, `logging.pattern.correlation` / a converter like `TenantLogConverter`) **and** to `ServiceLogCaptureAppender.PATTERN` (stored text).
- Matching is anchored to that header position only, never "anywhere in the line", so payload text cannot spoof a tag.

### 3. Tag as chunk fragment (collected log)
- `ServiceLogRecord` gets a `mapping` field (nullable), read from the event MDC in `ServiceLogCaptureAppender` (`mapperMapping`).
- `ServiceLogChunker.split` collects the set of mapping identifiers per chunk.
- `EventServiceLogSink` writes one fragment per identifier on the chunk event, e.g. `d11r_map_<identifier>: {}` (identifiers are generated alphanumerics — verify imported/legacy ones, see gate).
- `EventServiceLogEvents.read` adds `EventFilter.byFragmentType("d11r_map_<identifier>")` when a mapping is requested.
- Old chunks have no fragment and never match (acceptable; document it).

### 4. Reader / controller
- `ServiceLogsController.read(dateFrom, dateTo, mapping)`; validate `mapping` against `[A-Za-z0-9_-]+` before it becomes a fragment name (no injection into the query).
- `ServiceLogReader.read(tenant, from, to, mapping)`: after the (already filtered) chunks are fetched, split each chunk's text into records and keep those whose header carries the tag.
  - Record split: a record starts at a line matching `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}` followed by the level field; everything else is continuation. Tag test only on the header line.
  - `Page.lines` = lines actually returned; `Page.omitted` keeps the chunk value but the UI wording becomes "may include this mapping" when a filter is active.
- No change to cursor logic for the collected log (cursor = matching chunks' `dateTo`).

### 5. UI (`dynamic-mapper-ui/src/monitoring/logs/`)
- `LogRequest` gets `mapping?: string` (`service-logs.model.ts`); `CollectedServiceLogsService.load` adds `&mapping=`.
- Dropdown "Mapping" in the filter row of `service-logs.component.html`, next to "From"; options from the existing mapping service (name label, identifier value), plus "All".
- Changing the selection reloads from *latest*; **Download** respects the filter.
- **Empty filtered window**: stay in the filtered state, show "No lines for this mapping in this range", keep Previous/Next usable (fix the early `return` in `fetch()`).
- **Auto refresh with a filter**: fetch incrementally (`dateFrom` = last seen `dateTo`) instead of re-reading the latest window, or disable it.
- Native source: filter the loaded window client-side and label it ("filters the loaded lines only"); or hide the filter for native in a first iteration.
- Hint when the tenant's minimum level is above the level of per-message processing lines (most are `DEBUG`).

## Gate: fragmentType query (VERIFIED on the platform)

Option B depends on the Event API accepting `fragmentType` together with `type`, `source` and `dateFrom/dateTo` (plus `revert` and paging), and returning only events that carry that fragment.

**Result (2026-10-17, ck4.eu-latest.cumulocity.com, go-c8y-cli):** confirmed. One temp device, three events (`a`: fragments `d11r_map_aaa` + `d11r_map_bbb`; `b`: `bbb`; `c`: `ccc`), queried with `type + source + dateFrom -1h + dateTo +1d + pageSize 50`:

| Query | Result |
|---|---|
| `fragmentType=d11r_map_aaa` | `a` |
| `fragmentType=d11r_map_bbb` | `b a` (newest first, the default) |
| `fragmentType=d11r_map_ccc` | `c` |
| `fragmentType=d11r_map_zzz` | nothing |
| no fragment filter | `c b a` |
| `bbb` + `revert` | `a b` (oldest first) |

An event with several fragments is found under each of them, an unknown fragment yields an empty list (not an error), and `revert` works together with the filter. The temp device was deleted afterwards.

Not covered by that test (still to check during implementation):
- From the Java side, `EventFilter.byFragmentType(...)` chained with `byType/bySource/byDate`: the SDK may only support one at a time — in that case add the parameter via `QueryParam("fragmentType", …)` as `EventServiceLogEvents` already does for `revert`.
- `currentPage` paging together with `fragmentType` over more than one page (the test had 3 events, one page).
- Real chunk events are large (up to `maxChunkBytes` of text); confirm the fragment query is not slower on a tenant with many `d11r_serviceLog` events.

**If the Java-side or paging checks fail in a way that cannot be worked around:** fall back to Option A — keep everything above except §3, and add to `ServiceLogReader.scan` a `scannedUntil` cursor (separate from the displayed range, with the same-ms guard against the last *scanned* chunk), a UI "keep searching" state for empty-with-`more` pages, and accept the 500-event scan cost (+0.5–1 day, more tests).

Also verify before building: imported/legacy mappings never have identifiers with characters illegal in a fragment key; the number of fragments per chunk stays small (bounded by mappings that logged in one collection interval).

## Tests
- `MappingLogContextTest` (restore previous value, null/blank, nesting) — pattern of `TenantLogContextTest`.
- Router tests: tag present during processing of mapping A, absent after, never leaks to the next mapping on the same pooled thread.
- `ServiceLogCaptureAppenderTest`: record carries mapping; line text has the tag only when set.
- `ServiceLogChunkerTest`: fragment set per chunk; chunks without mapping records get none.
- `ServiceLogReaderTest`: record splitting with stack traces and multi-line payloads; spoof attempt (payload line containing `[m:other]` or a header-looking line); filter on / off yields the same cursors; empty filtered page.
- `ServiceLogsControllerTest`: `mapping` validation, 403 unchanged.
- UI specs: request carries `mapping`; empty filtered window keeps state; auto refresh incremental; Download respects filter.
- Remember: surefire only runs `*Test` classes (`mvn test`, JDK 25, see memory `build-java-version-and-mvn-shim`); the UI baseline has 31 pre-existing failures.

## Docs
`docs/feature/service-logs.md` and the in-app page under `dynamic-mapper-ui/public/docs` (what the filter covers, that old lines are not filterable, the level hint, native-log limitation).

## Estimate

| Part | Size |
|---|---|
| `MappingLogContext`, two call sites, pattern change, tests | 0.5 d |
| Record field, chunker, sink fragments, event query, reader record filter, controller, tests | 1.5 d |
| UI dropdown, request plumbing, empty/refresh handling, native client-side filter, specs | 0.5–1 d |
| Docs | 0.25 d |
| **Total (Option B)** | **~3 d** (Option A fallback: 3.5–4 d) |

## Open points
- Does a stricter `ServiceLogPolicy`/tenant level make the filter useless by default? (UI hint covers it; consider whether processing-level lines should be logged at INFO when a mapping has debug enabled.)
- Foreign-thread callbacks losing the tag — wrap case by case if they matter in practice.
