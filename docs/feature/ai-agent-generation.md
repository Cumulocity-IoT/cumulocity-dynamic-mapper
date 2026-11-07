# AI Agent Integration (Generating Substitutions and Smart Functions)

"Generate with AI" lets a user produce a mapping's JSONata substitutions or Smart Function
code from a chat conversation instead of writing them by hand, grounded in the mapping's
source/target templates. It is named as a headline capability in the project overview, but
until this page existed had no documentation describing what it actually does or requires.

The key architectural fact, easy to miss from the class name alone: **this repository does
not call an LLM itself.** It delegates to a separate Cumulocity platform microservice, the AI
Agent Manager, which is configured (by a platform admin, outside this project) with whichever
LLM provider it uses. This project's job is threefold: bootstrap two named agent definitions
on that platform service (declared in the microservice manifest), expose MCP tools to them (a
JSONata self-check and two "submit result" tools), and drive a chat UI in the browser against that platform's own agent-chat SDK component.

---

## Requirements

**What it is for.** Turning a mapping's source/target templates (and, optionally, a sample
payload) into working substitutions or Smart Function code without the user hand-writing
JSONata paths or JavaScript.

- **Two agents are available per tenant**, declared in the microservice manifest and provisioned by the platform, one for JSONata substitutions and one for Smart
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
- **The agent hands over its final result through a structured channel** (the `submit-substitutions` and
  `submit-smart-function` tools), not by the UI guessing which text block in the reply is the answer. A submission that
  is empty or malformed is rejected with an error the agent can correct. Replies from agent definitions that predate
  these tools are still scanned for code blocks.
- **A conversation survives closing the drawer.** Reopening the drawer for the same, unchanged mapping resumes the
  transcript (kept for the browser tab, lost on reload) and offers the earlier result again, unless the user edited the
  code or substitutions by hand in the meantime.
- **AI answers are checked, but the check only warns.** Problems found in a generated result are shown to the user,
  who can send them back to the agent; Save stays enabled.
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
- **The tenant AI Agent Manager kill switch is authoritative.** The UI checks
  `GET service/ai/settings` before listing agents or opening the chat. When `killSwitch` is
  `true`, it makes no request to an AI agent, disables AI generation in the mapper, and tells
  the user that AI capabilities are disabled in the tenant. Agent definitions are still declared in the manifest;
  declaring them does not send prompts. When the tenant opts out of telemetry (`telemetryOptOut`), the chat turns its
  analytics off.

---

## Implementation

### Manifest agent endpoints

The manifest's `agents` values are HTTP paths relative to the microservice, not classpath
or filesystem paths. Like the MCP example application, the service must serve each definition
over HTTP. `AgentDefinitionController` exposes the bundled JSON resources at:

- `/service/dynamic-mapper-service/agents/jsonata_agent.json`
- `/service/dynamic-mapper-service/agents/smartfunction_agent.json`

The manifest paths remain `agents/jsonata_agent.json` and `agents/smartfunction_agent.json`;
the platform gateway supplies the `/service/dynamic-mapper-service/` prefix. Packaging the
files in `src/main/resources/agents/` alone does not make them HTTP-accessible.
After deployment, authenticated GET requests to these URLs must return HTTP 200 with the
JSON definitions so the AI Agent Manager can retrieve them for automatic provisioning.

### Backend: agent registration, not generation: [`AIAgentService`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/ai/AIAgentService.java)

Despite the name, `AIAgentService` does not generate substitutions or code itself. The
actual chat/generation happens client-side (see below), talking directly to the platform
service and bypassing `dynamic-mapper-service` entirely. Someone looking for a "generate"
endpoint in the Java backend won't find one — that's expected.

- **Agents come from the manifest.** The two definitions are the JSON files
  `agents/jsonata_agent.json` and `agents/smartfunction_agent.json` (agent names
  `dynamic-mapper-jsonata-agent` and `dynamic-mapper-smart-function-agent`, `availability: SHARED`), each carrying
  its system prompt and the MCP tools it may call. The platform retrieves them over HTTP from
  `AgentDefinitionController` (`/agents/...`).
- `initializeAIAgents()` runs from `BootstrapService` on tenant subscription. It checks
  `checkAIAgentAvailable()` (`GET {baseURL}/service/ai/health`), lists the agents, and in every branch calls
  `addingAIAgentsToServiceConfiguration()`, which fills `ServiceConfiguration.jsonataAgent` /
  `smartFunctionAgent` with the default names when they are still `null`. The call to `createDefaultAIAgents()`
  is commented out: the method (which would `POST` an `AIAgent` built from `classpath:prompts/*.txt` to
  `{baseURL}/service/ai/agent/text`) is dead code, and the bundled agent JSON files are the source of truth. An
  admin can later repoint either setting at a different platform-side agent from Service Configuration → AI.
- **MCP tools.** `App.tools()` registers `AIAgentService` and
  [`MappingResultTools`](../../dynamic-mapper-service/src/main/java/dynamic/mapper/ai/MappingResultTools.java) as the
  tool callback provider of the MCP server `dynamic-mapper-mcp-server`:
  - `evaluate-jsonata-expression` (`AIAgentService.evaluateJsonataExpression()`): runs a candidate expression
    against sample JSON so the JSONata agent can self-check. JSONata agent only.
  - `submit-substitutions` (JSONata agent) and `submit-smart-function` (Smart Function agent): store nothing. They
    sanity-check the input (non-empty; every substitution has `pathSource` and `pathTarget` and a known
    `repairStrategy`; the code defines `function onMessage`) and throw otherwise, which reaches the agent as a tool
    error so it can resubmit. The UI reads the result from the tool call's input.
- The system prompts (in the agent JSON files, mirrored in
  [`jsonata_prompt.txt`](../../dynamic-mapper-service/src/main/resources/prompts/jsonata_prompt.txt) and
  [`smartfunction_prompt.txt`](../../dynamic-mapper-service/src/main/resources/prompts/smartfunction_prompt.txt))
  encode the rules the LLM must follow: the `Substitution` (`pathSource`/`pathTarget`/`expandArray`/…) and `Mapping`
  shapes, GraalVM-specific JavaScript constraints for Smart Functions (e.g. no `.forEach`/`.map` on Java-backed
  array payloads — classic indexed loops only), transport-field access (`msg.transportFields`, including broker metadata such as `header.<name>` and the `connectorType` of the sample message), and conditioning the
  `export { onMessage }` statement on whether ESM support is enabled. The agent is told to deliver the final result
  only through the submit tool.

### Frontend: agent resolution and availability — [`ai-agent.service.ts`](../../dynamic-mapper-ui/src/mapping/core/ai-agent.service.ts)

- `resolveRequiredAgentName()` maps a mapping's `TransformationType` to the
  configured agent name: `JSONATA`/`DEFAULT` → `ServiceConfiguration.jsonataAgent`,
  `SMART_FUNCTION` → `ServiceConfiguration.smartFunctionAgent`; anything else falls back to the deprecated
  `javaScriptAgent`.
- `getAISettings()` reads `GET service/ai/settings` (`killSwitch`, `telemetryOptOut`; a 404/405 means an older
  AI Agent Manager and counts as enabled; other failures count as unavailable). Cached for 30 seconds unless forced.
- `getAIAgents()` / `fetchAIAgents()` call `GET service/ai/agent` **directly
  against the platform service**, but only after `GET service/ai/settings` confirms the tenant
  kill switch is off. This bypasses `dynamic-mapper-service` — the code comments this
  endpoint as undocumented and not part of the SDK's `AIService`, and note that a 401/403
  there is indistinguishable from "no agents configured" from the UI's point of view. Results
  are cached in memory for 30 seconds.
- `toClientAgentDefinition()` adapts this project's
  `AgentTextDefinition`/`AgentObjectDefinition`
  ([`shared/mapping/ai-prompt.model.ts`](../../dynamic-mapper-ui/src/shared/mapping/ai-prompt.model.ts))
  to the SDK's `ClientAgentDefinition` (`@c8y/ngx-components/ai`), which the chat component
  consumes directly.
- Availability is checked at mapping-creation time in
  [`MappingTypeDrawerComponent`](../../dynamic-mapper-ui/src/mapping/mapping-create/mapping-type-drawer.component.ts)
  (`checkAIAgentAvailability`) so the "Generate with AI" entry point can be hidden or disabled
  gracefully when the AI Agent Manager isn't reachable, per the "must still work without AI"
  requirement above.
- The generation drawer independently refreshes the tenant settings before rendering
  `AgentChatComponent`, so a stale availability check cannot open the chat after the kill switch
  is enabled. The mapping-creation and editor affordances show a tenant-specific disabled notice.

### Frontend: the generation drawer — [`AiPromptComponent`](../../dynamic-mapper-ui/src/mapping/prompt/ai-prompt.component.ts)

Wraps `@c8y/ngx-components/ai/agent-chat`'s `AgentChatComponent`, configured with the resolved
`clientAgentDefinition`. Opened from the mapping stepper's transformation step
(`mapping-stepper.service.ts`) or from the mapping-type drawer.

- `buildMappingForAI()` strips `substitutions` out of the mapping and parses
  `sourceTemplate`/`targetTemplate` from their stored JSON-string form, plus attaches
  `supportESM` from `ServiceConfiguration` — this stripped object, not the live mapping, is
  what the agent sees.
- `groundingContextProvider` re-sends this mapping context as a
  `[MAPPING_CONTEXT]` fenced block alongside *every* user message, per `AgentChatComponent`'s
  contract, rather than embedding it once in the visible transcript — the full JSON can run to
  dozens of lines and would otherwise make the first visible chat message unreadable.
- `ngOnInit()` branches on `existingMapping` (set when the stepper configuration's `persistence` is `draft`) and mapping kind:
  - **Existing mapping**: offers "Review / Refine existing …" vs. "Generate new … from scratch" as
    suggestion chips; nothing is sent automatically.
  - **New mapping, Smart Function**: offers "Let AI propose a target structure" vs. "I want to
    provide a sample target payload" as chips.
  - **CREATE, JSONata**: sends a generate prompt immediately — see requirements above.
  `buildGenerateMessage()` is where the outbound-has-no-target-schema and
  inbound-has-a-typical-sample-payload hints (`SAMPLE_TEMPLATES_C8Y[targetAPI]`) live, so the
  agent generates code directly instead of pausing to ask for a format that structurally
  doesn't exist for that direction.
- `onToolResult()` is the primary channel: when the agent's `submit-smart-function` or `submit-substitutions` tool
  call completes without error, the component reads the result from the tool call's input and runs it through
  `acceptSmartFunction()` / `acceptSubstitutions()` (the same shape checks and validation as below). A submission
  for the wrong kind of mapping is ignored.
- `onMessageFinish()` fires once a streamed response completes. If a submit tool already delivered the result
  during this turn it does nothing more (the reply text may legitimately contain examples); otherwise it is the
  **fallback** for agent definitions deployed before the submit tools existed, and dispatches
  to one of two extractors depending on `isCodeMapping` (i.e. whether the mapping is a Smart Function mapping):
  - `checkIfResponseContainsSubstitutions()`: a response may contain several
    fenced code blocks (the agent illustrating an alternative before its final answer), so
    candidates are tried **most-recent-first** — last ```` ```json ```` block, then last
    generic ```` ``` ```` block, then the raw body if it looks like JSON — and validated by
    duck-typing (`pathSource`/`pathTarget`/`expandArray` present on every element). A missing
    `repairStrategy` is defaulted to `RepairStrategy.DEFAULT` here so downstream consumers
    never see `undefined`/`null`.
  - `checkIfResponseContainsJavaScript()`: mirrors the same most-recent-first
    strategy over ```` ```javascript ```` / generic fenced blocks, requiring a
    `function onMessage` to accept a candidate, then `applyESMExport()` appends
    `export { onMessage };` when ESM support is enabled and the export isn't already present.
  - **Both extractors are intentionally one-directional**: a response with no matching block
    (a clarifying question, an acknowledgement) leaves the previously-extracted
    `substitutions`/`generatedCode` and `valid` flag untouched rather than clearing them —
    normal in a multi-turn conversation, and this is what lets "Save" stay enabled across a
    back-and-forth.
- **Conversation memory:** [`AiChatHistoryService`](../../dynamic-mapper-ui/src/mapping/core/ai-chat-history.service.ts)
  keeps the transcript and the extracted result per mapping `identifier`, in memory only (per browser tab, lost on
  reload). `persistSession()` runs after each reply, on message changes, and from `save()`/`cancel()`, never from
  `ngOnDestroy`. `isStoredResultReusable()` offers the stored result again only if the conversation inputs are
  unchanged and the code/substitutions in the mapping are still what they were at open or the stored result itself.
  A resumed conversation shows no suggestion chips and sends no automatic first message.
- `save()` resolves the drawer's promise with either `generatedCode` (Smart Function) or
  `substitutions` (JSONata) back to the caller.

### Several sample messages from the Message Explorer

In the Message Explorer, the user can select 2 to 5 messages and run "Create mapping from selected messages". The messages must have the same payload type and direction (JSON with JSON, binary with binary), enforced by `samplePayloadKind()` and `canCombine()` in `message-explorer.component.ts`. The oldest message becomes the primary sample (`sourceTemplate`). The others travel as `additionalMessages` in `ExplorerMappingHandoff`, become `StepperConfiguration.additionalSamples`, and reach the agent as `additionalSamples` in the mapping context. They are used only when the user chooses a Smart Function generated with AI; otherwise only the primary message is used. They are stepper state and are never persisted with the mapping, so the backend `Mapping` model is unchanged. Existing tenants keep their stored agent prompts, so the `additionalSamples` guidance in `smartfunction_prompt.txt` takes effect after the agents are re-created.

### What is and is not validated

The AI path applies substitutions through a different route than the manual editor: the manual
path gates its Add/Update buttons on `isSubstitutionValid()`
([`substitution-management.service.ts`](../../dynamic-mapper-ui/src/mapping/service/substitution-management.service.ts), `isSubstitutionValid()`),
but that is a *button guard, not an invariant* — `replaceAllSubstitutions()` and
`addSubstitution()`, which the AI path calls directly, do not check it.

`AiPromptComponent` therefore runs its own checks on each answer before the result can be
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

- **The text-scraping extractors are now only a fallback.** With the submit tools the result arrives as structured
  data, but the tool input is still duck-typed, not schema-validated, in the UI. The most-recent-first,
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
