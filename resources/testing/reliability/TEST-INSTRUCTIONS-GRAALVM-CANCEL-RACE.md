# Test Instructions: Worker Interrupted Mid-GraalVM-Context-Borrow

This document is an instruction set for a **not-yet-implemented** test, written during a deeper
review of the Camel-removal refactoring (`feature/remove-camel`) after two related concurrency
bugs were found and fixed by Copilot PR review (multi-mapping cancellation not reaching
per-mapping workers; a failing request aborting its siblings in `SendInboundProcessor`). This is
the third, not-yet-closed item from that review: a code path that changed from *dead* to *live*
as a side effect of the cancellation fix, and was never exercised by a dedicated test.

## Why this needs a dedicated test, not just a read-through

### Background: what changed

Before the fix, cancelling in-flight processing for a message that matched more than one mapping
(`ProcessingResultWrapper.cancelProcessing()`) could only interrupt the *outer* coordinator
thread — which, while fanning out per-mapping work, was itself blocked in
`CompletableFuture.join()`, a call that does not respond to `Thread.interrupt()`. Concretely:
**a per-mapping worker thread was never actually interrupted by a timeout/cancellation.** It kept
running to completion (or crashed on its own) regardless of what the caller did.

The fix (`InboundMessageRouter`/`OutboundMessageRouter`) submits each per-mapping worker via
`ExecutorService.submit(...)` (a real, interruptible `Future`) and registers it with
`ProcessingResultWrapper.registerWorkerFuture(...)`, so `cancelProcessing()` now calls
`.cancel(true)` on every per-mapping worker directly.

### The consequence

A SMART_FUNCTION (Smart Function / JavaScript) mapping's worker thread can now genuinely receive
`Thread.interrupt()` **while it is in the middle of running JavaScript, holding a
`PooledGraalContext` borrowed from `GraalVMContextService`'s pool** — a timing window that was
*unreachable* before this fix, because cancellation never reached that deep. The
borrow/kill/return machinery for this exact scenario already exists and looks correct on
inspection (see "Relevant production code" below), but "looks correct on inspection" is exactly
the confidence level the two Copilot findings also had before dedicated tests were written for
them. This class of bug — a resource-pool accounting race under concurrent cancellation — is
also the theme of the memory-leak investigation earlier in this session (GraalVM `Context`/
`Engine` objects are large, off-heap-backed, and expensive to leak).

### What could go wrong, specifically

1. **Double-decrement or missed-decrement of `engineActiveContexts`** (the in-flight-context
   counter `GraalVMContextService` uses to know when it's safe to retire an `Engine`) if both the
   interrupt-driven exception path and the explicit `cancelAction`-driven kill path each try to
   release the same context.
2. **A killed context gets returned to the pool anyway** (should never happen —
   `PooledGraalContext.isKilled()` gates this in `returnContext()` — but only a live test proves
   the gate is actually reached under real interrupt timing, not just when called directly).
3. **A worker thread that doesn't actually terminate** — e.g. if the interrupt races with GraalVM
   internally swallowing it, or the `cancelAction`'s `pooledRef.kill()` throws before completing
   the context close, leaving the thread stuck.
4. **An exception escaping past `AbstractFlowProcessor.process()`** uncaught, instead of being
   normalized into a `ProcessingException` and recorded on the context — which would break the
   router's `consolidationProcessor.process(context)` contract (every terminal path must reach it
   — see the memory-leak investigation for why that matters).
5. **Under concurrent load** (multiple mappings/messages hitting the *same* pool key
   simultaneously, some cancelled, some not) — races in the `CopyOnWriteArrayList`/
   `AtomicInteger`/`ConcurrentHashMap` bookkeeping that a single-shot test wouldn't surface.

## Relevant production code (read these before writing the test)

- `dynamic-mapper-service/src/main/java/dynamic/mapper/processor/runtime/ProcessingResultWrapper.java`
  — `cancelProcessing()`: sets `cancellationRequested`, cancels `processingResult` and every
  registered `workerFutures` entry (the fix), then runs every registered `cancelActions` entry.
- `dynamic-mapper-service/src/main/java/dynamic/mapper/processor/AbstractFlowProcessor.java`
  (~line 150-240) — registers a `cancelAction` that calls `pooledRef.kill()` (pooled path) or
  `directRef.close(true)` (non-pooled path) before running `processSmartMapping(context)`;
  unregisters it and calls `context.close()` in a `finally`.
- `dynamic-mapper-service/src/main/java/dynamic/mapper/processor/runtime/PooledGraalContext.java`
  — `kill()`/`isKilled()`/`closeQuietly()`, all `AtomicBoolean.compareAndSet`-guarded so `kill()` is
  documented as "safe to call from any thread" concurrently with the executing thread. This is
  the class whose documented thread-safety contract this test actually exercises for the first
  time under real concurrent interrupt timing.
- `dynamic-mapper-service/src/main/java/dynamic/mapper/core/GraalVMContextService.java` —
  `borrowOrCreateContext()`, `returnContext()` (checks `pooled.isKilled()` before deciding whether
  to put it back in `contextPool`), `releaseEngine()` (decrements `engineActiveContexts`),
  `DEFAULT_POOL_SIZE = 20`.
- `dynamic-mapper-service/src/main/java/dynamic/mapper/processor/runtime/ProcessingContext.java`
  (~line 400-450) — `close()`: for the pooled path, does *not* close the GraalVM `Context`
  directly, just nulls fields and invokes `engineReleaseAction` (set by
  `AbstractEnrichmentProcessor` to call `GraalVMContextService.returnContext(...)`).

## Test approach

A pure-mock unit test **cannot** exercise this — the race depends on real thread scheduling, a
real GraalVM `Context` actually executing JavaScript, and a real `Thread.interrupt()` landing
mid-execution. This needs an integration-style test using the real `GraalVMContextService` and a
real (or close to real) `AbstractFlowProcessor` pipeline.

### Step 1 — Synchronize on "worker has borrowed the context and started JS", not on timing luck

Don't rely on `Thread.sleep(...)` guesses to land the interrupt mid-execution — that's exactly
how this kind of test becomes flaky. Use a JavaScript payload that:

- Signals a `CountDownLatch`/`CyclicBarrier` (via a JS-callable bridge object injected into the
  GraalVM bindings, the same mechanism `AbstractFlowProcessor` already uses for the
  `isCancelled()` helper — see `processSmartMapping()`) the instant it starts running, so the
  test thread knows the context has genuinely been borrowed and JS execution has begun.
- Then busy-loops (e.g. `while(true) { for (var i = 0; i < 1e6; i++) {} }`) **without** checking
  the injected cancellation helper — deliberately, so the only way this ever terminates is via
  `Thread.interrupt()` / `Context.close(true)`, not a cooperative check. This is the worst case
  the fix needs to handle, and the one the two Copilot-flagged bugs both turned out to hinge on
  ("the code technically has a mechanism, but nothing forces the JS to cooperate").

```java
String code = """
    function onMessage(msg, context) {
        __testSync.workerStarted();
        while (true) { for (var i = 0; i < 1000000; i++) {} }
    }
    """;
```

### Step 2 — Drive the real pipeline on a real thread, cancel from the test thread

- Build a real `SendInboundProcessor`/`FlowInboundProcessor`-equivalent pipeline (or drive
  `AbstractFlowProcessor.process(context)` directly against a minimal concrete subclass — check
  whether `FlowInboundProcessorTest`/`FlowOutboundProcessorTest` already have a pattern for this;
  several `*ProcessorTest` classes in this codebase build a real `GraalVMContextService` bean
  rather than mocking it specifically for GraalVM-execution tests — follow that precedent instead
  of inventing a new one).
- Submit the pipeline run via `ExecutorService.submit(...)` (a real `Future`, matching
  production), register it with a real `ProcessingResultWrapper` via `registerWorkerFuture(...)`,
  exactly as `InboundMessageRouter` now does.
- Block on the `CountDownLatch` from Step 1 until the worker signals it has started.
- Call `resultWrapper.cancelProcessing()` from the test thread.
- Assert the submitted `Future` completes (via `future.get(<bounded timeout>, TimeUnit.SECONDS)`,
  a few seconds should be generous) rather than hanging — this is the "worker thread that doesn't
  actually terminate" failure mode from finding #3 above.

### Step 3 — Assertions

1. **No uncaught exception**: `future.get(...)` must not throw anything other than what
   `AbstractFlowProcessor`'s own catch/`handleProcessingError` path is expected to produce (finding
   #4). Confirm `context.hasError()` is true and the recorded error reflects a cancellation
   (`PolyglotException.isCancelled()`), not a generic failure.
2. **Engine accounting returns to baseline**: read `engineActiveContexts` for the tenant's engine
   before the test and after the cancelled worker completes (via a package-visible test hook, or
   reflection if none exists — check whether `GraalVMContextService` already exposes anything
   test-friendly before adding reflection) — it must return to the pre-test value, not stay
   incremented (finding #1).
3. **Killed context never returns to the pool**: after cancellation, borrow a context for the
   *same* `poolKey` again (same tenant/mapping/code) and confirm it's either a freshly-created one
   or otherwise not the same instance that was killed — the simplest black-box way is
   identity-comparing (`==`) the newly-borrowed `PooledGraalContext`/its `Context` against the one
   captured before cancellation (finding #2).
4. **Repeat under concurrency**: run Steps 1-3 for N (e.g. 10-20) mappings sharing the *same* pool
   key simultaneously, cancelling a random subset while letting others complete normally, and
   assert all of the above hold in aggregate, plus that the never-cancelled workers' results are
   unaffected (finding #5 — this is the case most likely to surface something a single-shot test
   misses, since `CopyOnWriteArrayList`/`ConcurrentHashMap`/`AtomicInteger` bugs are usually races,
   not always-reproducible sequential logic errors).

### Step 4 — Also cover the non-pooled path

`AbstractEnrichmentProcessor` has both a pooled-context branch and a direct (non-pooled)
`graalContext` branch (visible in `ProcessingContext.close()`'s `if (pooledGraalContext != null)
{...} else {...}` split). Repeat at least the single-worker version of Steps 1-3 for whatever
mapping configuration takes the non-pooled path, since the cancellation mechanics differ slightly
(`directRef.close(true)` vs `pooledRef.kill()`).

## Suggested location and naming

New test class, since this spans `GraalVMContextService` + `AbstractFlowProcessor` +
`ProcessingResultWrapper` interaction and doesn't belong to any single existing `*ProcessorTest`:

```
dynamic-mapper-service/src/test/java/dynamic/mapper/core/GraalVMContextCancellationRaceTest.java
```

Run just this class once written: `cd dynamic-mapper-service && mvn test -Dtest=GraalVMContextCancellationRaceTest`.

## Suggested order of work

1. Confirm the test-construction approach (Step 1-2) against existing precedent — check
   `FlowInboundProcessorTest`/`AbstractFlowProcessorTest` for how they already drive real GraalVM
   execution in tests, and reuse that setup rather than inventing a new one.
2. Implement the single-worker cancellation case (Steps 1-3, N=1) first — cheapest, most
   deterministic, closes the "does the documented mechanism actually work under real interrupt
   timing" question.
3. Implement the concurrent-N case (Step 3.4) — this is the one most likely to actually find a
   bug, since the single-worker case mostly validates wiring, not races.
4. Implement the non-pooled-path variant (Step 4) only after 2-3 are green, since it's lower risk
   (simpler `close(true)` call, no pool bookkeeping) and mostly there for completeness.
5. If any assertion fails: that's a genuine finding, not a test bug — do not loosen the assertion
   to make it pass; report it the same way the two Copilot-found issues were reported (root cause,
   fix, regression test), since it would be the third bug in this same cancellation-parallelism
   area of the refactor.
