# AI Agent Integration (Generating Substitutions and Smart Functions)

"Generate with AI" lets a user produce a mapping's JSONata substitutions or Smart Function
code from a chat conversation instead of writing them by hand, grounded in the mapping's
source/target templates. It is named as a headline capability in the project overview, but
until this page existed had no documentation describing what it actually does or requires.

The key architectural fact, easy to miss from the class name alone: **this repository does
not call an LLM itself.** It delegates to a separate Cumulocity platform microservice, the AI
Agent Manager, which is configured (by a platform admin, outside this project) with whichever
LLM provider it uses. This project's job is threefold: bootstrap two named agent definitions
on that platform service, expose a JSONata self-check tool to one of them via MCP, and drive a
chat UI in the browser against that platform's own agent-chat SDK component.

---

## Requirements

**What it is for.** Turning a mapping's source/target templates (and, optionally, a sample
payload) into working substitutions or Smart Function code without the user hand-writing
JSONata paths or JavaScript.

- **Two agents are provisioned per tenant**, one for JSONata substitutions and one for Smart
  Functions — a mapping's `transformationType` determines which one a "Generate with AI"
  request uses.
- **Generation must never write into the tenant's data or run against a real device** — it
  operates purely on the mapping's own templates and chat context; nothing it produces is
  applied until the user explicitly saves it into the mapping.
- **The agent is grounded in the current mapping's state** (direction, target API, source and
  target templates, `useExternalId`, whether ESM exports are required) on every message, not
  just the first — so refining an answer mid-conversation still has full context.
- **A response the AI produces is never applied automatically.** The user reviews the
  extracted substitutions/code and explicitly saves; a partial or unparseable response must
  not silently overwrite a previously valid result mid-conversation.
- **JSONata generation for a brand-new (CREATE-mode) mapping starts automatically** — there is
  nothing yet to review, so the assistant is prompted immediately rather than waiting for the
  user to type a first message.
- **Smart Function generation for a brand-new mapping asks first**, because unlike JSONata
  substitutions a Smart Function's target has no fixed template to aim at — the user chooses
  between "let AI propose a structure" and "I'll provide a sample target payload".
  Outbound Smart Functions are the one case with no target schema at all (the function itself
  builds whatever payload the target broker/protocol expects), so the agent is explicitly told
  not to pause and ask for one.
- **Editing an existing mapping (UPDATE mode) offers a choice** between reviewing/refining what
  is already there and generating a replacement from scratch — it must not discard existing
  substitutions/code without the user asking for that.
- **The underlying AI provider (Anthropic, OpenAI, etc.) is configured entirely in the AI Agent
  Manager platform service, not in this project** — a deliberate non-goal: this codebase does
  not manage API keys, model choice, or provider failover.
- **If the AI Agent Manager is unavailable, mapping creation/editing must still work** without
  AI — "Generate with AI" is an optional accelerator, not a hard dependency of the mapping
  editor.

---

## Implementation

### Backend: agent bootstrap, not generation — [`AIAgentService`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/core/AIAgentService.java)

Despite the name, `AIAgentService` does not generate substitutions or code itself; it
provisions agent *definitions* on the external AI Agent Manager and exposes one MCP tool. The
actual chat/generation happens client-side (see below), talking directly to the platform
service and bypassing `dynamic-mapper-service` entirely. Someone looking for a "generate"
endpoint in the Java backend won't find one — that's expected.

- `initializeAIAgents()` (line 78) runs from `BootstrapService` on tenant subscription. It
  checks `checkAIAgentAvailable()` — `GET {baseURL}/service/ai/health` (lines 125-148) — and,
  if the two well-known agents don't already exist, calls `createDefaultAIAgents()` and
  records their names into `ServiceConfiguration.jsonataAgent` /
  `ServiceConfiguration.smartFunctionAgent` (`addingAIAgentsToServiceConfiguration()`, line
  109). An admin can later repoint either setting at a different platform-side agent from
  Service Configuration → AI.
- `createDefaultAIAgents()` (lines ~170-205) loads a system prompt per agent from
  `classpath:prompts/*.txt` —
  [`jsonata_prompt.txt`](../../dynamic-mapper-service/src/main/resources/prompts/jsonata_prompt.txt)
  and
  [`smartfunction_prompt.txt`](../../dynamic-mapper-service/src/main/resources/prompts/smartfunction_prompt.txt)
  — and `POST`s an `AIAgent` definition (name, system prompt, type, MCP tool wiring) to
  `{baseURL}/service/ai/agent/text`. Only the JSONata agent is wired to an MCP tool
  (`MCPUsage` naming the `evaluateJsonataExpression` tool).
- `evaluateJsonataExpression()` (lines ~285-302, `@Tool`-annotated, Spring AI) lets the
  JSONata agent actually run a candidate expression against sample JSON mid-conversation and
  see the real result — a self-check before answering, rather than emitting an expression it
  hasn't verified. Registered as an MCP tool callback in `App.java`
  (`ToolCallbackProvider tools(AIAgentService aiAgentService)`).
- The system prompts encode the rules the LLM must follow: the `Substitution`
  (`pathSource`/`pathTarget`/`expandArray`/…) and `Mapping` shapes it must conform to,
  GraalVM-specific JavaScript constraints for Smart Functions (e.g. no `.forEach`/`.map` on
  Java-backed array payloads — classic indexed loops only), transport-field access
  (`msg.transportFields`), and conditioning the `export { onMessage }` statement on whether
  ESM support is enabled.

### Frontend: agent resolution and availability — [`ai-agent.service.ts`](../../dynamic-mapper-ui/src/mapping/core/ai-agent.service.ts)

- `resolveRequiredAgentName()` (lines 40-54) maps a mapping's `TransformationType` to the
  configured agent name: `JSONATA`/`DEFAULT` → `ServiceConfiguration.jsonataAgent`,
  `SMART_FUNCTION` → `ServiceConfiguration.smartFunctionAgent`.
- `getAIAgents()` / `fetchAIAgents()` (lines 95-138) call `GET service/ai/agent` **directly
  against the platform service**, bypassing `dynamic-mapper-service` — the code comments this
  endpoint as undocumented and not part of the SDK's `AIService`, and note that a 401/403
  there is indistinguishable from "no agents configured" from the UI's point of view. Results
  are cached in memory for 30 seconds.
- `toClientAgentDefinition()` (lines 61-74) adapts this project's
  `AgentTextDefinition`/`AgentObjectDefinition`
  ([`mapping/shared/ai-prompt.model.ts`](../../dynamic-mapper-ui/src/mapping/shared/ai-prompt.model.ts))
  to the SDK's `ClientAgentDefinition` (`@c8y/ngx-components/ai`), which the chat component
  consumes directly.
- Availability is checked at mapping-creation time in
  [`MappingTypeDrawerComponent`](../../dynamic-mapper-ui/src/mapping/mapping-create/mapping-type-drawer.component.ts)
  (`checkAIAgentAvailability`) so the "Generate with AI" entry point can be hidden or disabled
  gracefully when the AI Agent Manager isn't reachable, per the "must still work without AI"
  requirement above.

### Frontend: the generation drawer — [`AIPromptComponent`](../../dynamic-mapper-ui/src/mapping/prompt/ai-prompt.component.ts)

Wraps `@c8y/ngx-components/ai/agent-chat`'s `AgentChatComponent`, configured with the resolved
`clientAgentDefinition`. Opened from the mapping stepper's transformation step
(`mapping-stepper.service.ts`) or from the mapping-type drawer.

- `buildMappingForAI()` (lines 290-298) strips `substitutions` out of the mapping and parses
  `sourceTemplate`/`targetTemplate` from their stored JSON-string form, plus attaches
  `supportESM` from `ServiceConfiguration` — this stripped object, not the live mapping, is
  what the agent sees.
- `groundingContextProvider` (lines 216-231) re-sends this mapping context as a
  `[MAPPING_CONTEXT]` fenced block alongside *every* user message, per `AgentChatComponent`'s
  contract, rather than embedding it once in the visible transcript — the full JSON can run to
  dozens of lines and would otherwise make the first visible chat message unreadable.
- `ngOnInit()` (lines 114-189) branches on `editorMode` and mapping kind:
  - **UPDATE**: offers "Review / Refine existing …" vs. "Generate new … from scratch" as
    suggestion chips; nothing is sent automatically.
  - **CREATE, Smart Function**: offers "Let AI propose a target structure" vs. "I want to
    provide a sample target payload" as chips.
  - **CREATE, JSONata**: sends a generate prompt immediately — see requirements above.
  `buildGenerateMessage()` (lines 233-274) is where the outbound-has-no-target-schema and
  inbound-has-a-typical-sample-payload hints (`SAMPLE_TEMPLATES_C8Y[targetAPI]`) live, so the
  agent generates code directly instead of pausing to ask for a format that structurally
  doesn't exist for that direction.
- `onMessageFinish()` (lines 322-341) fires once a streamed response completes, and dispatches
  to one of two extractors depending on `isCodeMapping` (i.e. whether `transformationType` is
  `SMART_FUNCTION`):
  - `checkIfResponseContainsSubstitutions()` (lines 397-447): a response may contain several
    fenced code blocks (the agent illustrating an alternative before its final answer), so
    candidates are tried **most-recent-first** — last ```` ```json ```` block, then last
    generic ```` ``` ```` block, then the raw body if it looks like JSON — and validated by
    duck-typing (`pathSource`/`pathTarget`/`expandArray` present on every element). A missing
    `repairStrategy` is defaulted to `RepairStrategy.DEFAULT` here so downstream consumers
    never see `undefined`/`null`.
  - `checkIfResponseContainsJavaScript()` (lines 349-382): mirrors the same most-recent-first
    strategy over ```` ```javascript ```` / generic fenced blocks, requiring a
    `function onMessage` to accept a candidate, then `applyESMExport()` appends
    `export { onMessage };` when ESM support is enabled and the export isn't already present.
  - **Both extractors are intentionally one-directional**: a response with no matching block
    (a clarifying question, an acknowledgement) leaves the previously-extracted
    `substitutions`/`generatedCode` and `valid` flag untouched rather than clearing them —
    normal in a multi-turn conversation, and this is what lets "Save" stay enabled across a
    back-and-forth.
- `save()` resolves the drawer's promise with either `generatedCode` (Smart Function) or
  `substitutions` (JSONata) back to the caller.

### What is and is not validated

The AI path applies substitutions through a different route than the manual editor: the manual
path gates its Add/Update buttons on `isSubstitutionValid()`
([`substitution-management.service.ts:30-36`](../../dynamic-mapper-ui/src/mapping/service/substitution-management.service.ts#L30-L36)),
but that is a *button guard, not an invariant* — `replaceAllSubstitutions()` and
`addSubstitution()`, which the AI path calls directly, do not check it.

`AIPromptComponent` therefore runs its own checks on each answer before the result can be
applied, and reports what it finds in a warning panel in the drawer, with a button that sends
the problems back to the agent as a follow-up turn. The checks are deliberately **warnings, not
a hard gate** — Save stays enabled, because an expression can fail against the *sample* template
while being correct for real payloads, and that judgement belongs to the user.

| Defect in AI output | Caught where? |
|---|---|
| Invalid JSONata in `pathSource` | **Drawer** (`validateSubstitutions()`) *and* rejected at save by `MappingValidator` → HTTP 422, naming the substitution index and the parser's reason, so it is caught even when substitutions are posted straight to the API. See [mapping-validation.md](mapping-validation.md#substitution-expressions-must-be-runnable). |
| Empty `pathSource` / `pathTarget` | Rejected at save by `MappingValidator` → HTTP 422, naming which of the two paths is empty. |
| Identity mapped to the wrong `_IDENTITY_` token for the mapping's `useExternalId` | **Drawer** (`identityTokenIssue()`). Neither the frontend nor the backend validator rejects it — both accept either token — so this check exists only here. |
| Duplicate `pathTarget` | **Drawer** (`duplicateTargetIssues()`). Still not rejected downstream, and handled inconsistently there: bulk replace keeps both, per-item add collapses them. |
| Syntactically broken / truncated Smart Function code | **Drawer** (`validateSmartFunctionCode()`), by parsing the extracted code after stripping module syntax. Degrades to "no finding" if `Function()` is blocked by a Content-Security-Policy. |
| Smart Function that fails to compile on the backend | **Service event** (`MAPPING_ACTIVATION_ERROR_EVENT_TYPE`) raised by the activation warm-up, so an active-but-uncompilable mapping is visible rather than only logged. |
| Invalid `repairStrategy` | Coerced back to `DEFAULT` in the extractor, so it never reaches Jackson (which would reject it as an opaque HTTP 400). |
| Missing/duplicate `_IDENTITY_` substitution (INBOUND JSONata) | `MappingValidator` → HTTP 422 at save. Skipped entirely for `SMART_FUNCTION`. |
| Invalid `targetAPI` enum value | Jackson, as an HTTP 400. |
| `pathTarget` absent from the target template (with `repairStrategy=DEFAULT`) | **Still nowhere.** JSONata returns `undefined` for a missing path rather than throwing, so evaluation cannot detect it, and checking template membership directly would false-positive on `CREATE_IF_MISSING` and on `_IDENTITY_` tokens. Fails at runtime with `PathNotFoundException`. |

The split is deliberate. Unparseable JSONata and blank paths are rejected server-side, because
such a mapping could never run under any circumstances — so refusing it cannot break anything
that works. Everything else stays a drawer warning: a duplicate `pathTarget` or a mismatched
identity token can describe a mapping that works today, and rejecting those at save would make
an existing mapping uneditable. That means a caller posting substitutions straight to
`POST /mapping` still bypasses the warning-level checks.

Sandboxing is unaffected by the AI path — generated code goes through the same
`allowHostAccess` / `allowHostClassLookup` allowlist in `GraalVMContextService` as
hand-written code. The AI cannot introduce a capability a human author could not; the
difference is purely that nobody necessarily reviewed it.

### Known gotchas

- **Extraction is regex plus duck-typing, not schema validation.** The most-recent-first,
  multi-fallback matching strategy in both extractors exists because a naive "first code block"
  match was observed to grab an earlier example instead of the model's actual final answer.
- **The full mapping is sent to an external LLM on every message.** `groundingContextProvider`
  re-sends the mapping (including source/target templates, which for an Explorer-seeded mapping
  contain a real captured device payload) with each turn. Where that data goes is governed by
  the AI Agent Manager's provider configuration, not by anything in this project.
- **`service/ai/agent` is an undocumented platform endpoint**, called directly from the
  browser — a 401/403 from it looks identical, from the UI's perspective, to "no AI agents
  configured for this tenant".
- **Provider/model choice is entirely out of this project's control** — if generation quality
  or behavior needs to change, the fix is almost always a system-prompt change
  (`jsonata_prompt.txt` / `smartfunction_prompt.txt`) or an AI Agent Manager configuration
  change, not a code change here.
