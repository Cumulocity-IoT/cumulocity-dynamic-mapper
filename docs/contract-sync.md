# Keeping the runtime contract in sync

The Dynamic Mapper has **one** runtime contract — what a Smart Function or Java Extension can
call, what `msg` contains, what a returned object may hold — and **nine** places that describe it.
None of them are generated from the runtime. Every one can drift silently: the build stays green,
the tests stay green, and the first symptom is a user following documentation that is wrong.

This page is the strategy for preventing that. For the Smart Function TypeScript types
specifically, [smart-function-type-sync.md](smart-function-type-sync.md) goes deeper; this page is
the umbrella and covers the other eight surfaces.

---

## 1. The principle: one authority, everything else mirrors it

The contract is **the code that constructs the objects handed to user code**. Not the interfaces,
not the docs — the construction sites.

| The contract | Authoritative file |
|---|---|
| What `context.*` offers a Smart Function | `processor/runtime/SmartFunctionContext.java` |
| What `context.*` offers a Java Extension | `processor/model/JavaExtensionContext.java` (extends `DataPrepContext`) |
| What `msg` contains | `processor/model/InputMessage.java`, populated by `FlowInboundProcessor.createInputMessage` and `FlowOutboundProcessor.createInputMessage` |
| What an inbound function may return | `processor/model/CumulocityObject.java` |
| What an outbound function may return | `processor/model/DeviceMessage.java` |
| Which `_CONTEXT_DATA_` keys do anything | `inbound/processor/SubstitutionResultInboundProcessor.java`, `FlowResultInboundProcessor.java`, `outbound/processor/SubstitutionResultOutboundProcessor.java` |
| Allowed enum values | `CumulocityType`, `MappingAction`, `Destination`, `RepairStrategy`, `API` |

Two consequences worth internalising:

- **An interface is not the contract.** `DataPrepContext` declares 13 methods; the object actually
  passed to JavaScript (`SmartFunctionContext`) has 15. Auditing the interface alone under-reports.
- **A field existing is not the same as it being populated.** `InputMessage` has 8 fields, but
  outbound passes `null` for three of them. Declaring them without saying so is how a documented
  field becomes a runtime `undefined`.

---

## 2. The nine surfaces

| # | Surface | Location | Fails as |
|---|---|---|---|
| 1 | TypeScript type definitions | `dynamic-mapper-smart-function/src/types/` | Author-time: valid code won't compile, or invalid code does |
| 2 | Mock context helpers | same file, `createMockRuntimeContext*` | Tests pass against an API that no longer matches |
| 3 | JS code templates | `dynamic-mapper-service/src/main/resources/templates/*.js` | Users copy a template that throws |
| 4 | Java extension interfaces | `processor/extension/ProcessorExtension{Inbound,Outbound}.java` | Extension authors code against the wrong shape |
| 5 | AI prompts | `dynamic-mapper-service/src/main/resources/prompts/*.txt` | The AI generates mappings using APIs that don't exist — **silent, and at scale** |
| 6 | In-app documentation | `dynamic-mapper-ui/public/docs/*.md` | Users follow instructions that don't work |
| 7 | Editor completion + hover | `dynamic-mapper-ui/src/shared/mapping/stepper.model.ts` | Autocomplete offers phantom members |
| 8 | Repo documentation | `docs/**/*.md` | Contributors build on stale assumptions |
| 9 | OpenAPI spec | `resources/openAPI/openapi.json` | Generated clients and 403 messages name wrong roles |

Surface 5 deserves the most care. A wrong prompt is not one wrong page — it is every mapping the
AI generates from then on, and the user has no reason to suspect the tool.

---

## 3. Executable checks

These are the actual commands used in the audits, not illustrations. Run them from the repo root.

### 3.1 Context API across all surfaces

```bash
python3 - <<'PY'
import re
S='dynamic-mapper-service/src/main/java/dynamic/mapper/'
runtime={n for t,n,a in re.findall(r'^\s*public\s+([\w<>,\[\]\. ?]+?)\s+(\w+)\(([^)]*)\)',
        open(S+'processor/runtime/SmartFunctionContext.java').read(), re.M) if not n[0].isupper()}
runtime |= {'logMessage'}            # default method on the interface
runtime -= {'setClientId','setConfig'}   # host-side setters, not script API

T=open('dynamic-mapper-smart-function/src/types/smart-function-dynamic-mapper.types.ts').read()
D=open('dynamic-mapper-smart-function/src/types/dataprep.types.ts').read()
i=T.index('export interface SmartFunctionContext'); j=T.index('export interface', i+10)
ts=set(re.findall(r'^\s{2}(\w+)\s*[(<]', T[i:j], re.M)) | set(re.findall(r'^\s{2}(\w+)\s*[(<]', D, re.M))

E=open('dynamic-mapper-ui/src/shared/mapping/stepper.model.ts').read()
k=E.index("name: 'SmartFunctionContext'")
editor=set(re.findall(r"\{ name: '(\w+)', parameters", E[k:k+4500]))

docs=open('dynamic-mapper-ui/public/docs/smartfunction.md').read()
prompt=open('dynamic-mapper-service/src/main/resources/prompts/smartfunction_prompt.txt').read()

for label, S_ in [('ts-defs',ts), ('editor',editor)]:
    print(f'{label:<10} missing={sorted(runtime-S_) or "none"}  phantom={sorted(S_-runtime-{"runtime","payload"}) or "none"}')
for label, text in [('docs',docs), ('prompt',prompt)]:
    print(f'{label:<10} missing={sorted(m for m in runtime if not re.search(rf"\b{m}\b", text)) or "none"}')
PY
```

### 3.2 `msg` shape

```bash
python3 - <<'PY'
import re
J='dynamic-mapper-service/src/main/java/dynamic/mapper/processor/model/InputMessage.java'
im=set(re.findall(r'^\s*public final [\w<>,\[\]\. ]+ (\w+);', open(J).read(), re.M))
T=open('dynamic-mapper-smart-function/src/types/smart-function-dynamic-mapper.types.ts').read()
def fields(name):
    a=T.index(f'export interface {name}'); b=T.index('{',a); d=0; k=b
    while k<len(T):
        if T[k]=='{': d+=1
        elif T[k]=='}':
            d-=1
            if d==0: break
        k+=1
    return set(re.findall(r'^\s{2}(?:readonly\s+)?(\w+)\??\s*[:?]', T[b:k], re.M))
for n in ['DynamicMapperDeviceMessage','OutboundMessage']:
    print(f'{n:<28} missing={sorted(im-fields(n)) or "none"}')
PY
```

### 3.3 `_CONTEXT_DATA_` keys the prompt teaches vs the runtime honours

```bash
# Runtime: keys actually read in the inbound result path
grep -rhoE '"(deviceName|deviceType|deviceFragments|deviceGroups|processingMode|attachmentName|attachmentType|attachmentData|api)"' \
  dynamic-mapper-service/src/main/java/dynamic/mapper/processor/inbound/ | sort -u

# Prompt: keys documented for INBOUND (stop at the OUTBOUND heading)
awk '/For INBOUND mappings/,/For OUTBOUND mappings/' \
  dynamic-mapper-service/src/main/resources/prompts/jsonata_prompt.txt \
  | grep -oE '_CONTEXT_DATA_\.\w+' | sort -u
```

### 3.4 Templates use only APIs that exist

```bash
# Every context call in every template must appear in the runtime list from 3.1
grep -rhoE 'context\.(\w+)\s*\(' dynamic-mapper-service/src/main/resources/templates/*.js \
  | sed 's/context\.//;s/(//' | sort -u
```

### 3.5 In-app doc images actually ship

Already automated and **failing the build**: `dynamic-mapper-ui/scripts/optimize-images.js` runs on
`prebuild` and errors if a `public/docs/*.md` image has no `buildTime.copy` entry in
`cumulocity.config.ts`. This is the model to copy for the checks above.

---

## 4. Change triggers

| If you change… | Also update |
|---|---|
| `SmartFunctionContext.java` (any public method) | TS defs + both mock helpers, editor provider `stepper.model.ts`, `smartfunction.md`, `smartfunction_prompt.txt`, at least one template |
| `InputMessage.java` or either `createInputMessage` | TS `DynamicMapperDeviceMessage` / `OutboundMessage`, editor provider, `smartfunction.md`, prompt |
| `CumulocityObject.java` / `DeviceMessage.java` | TS defs, editor provider, `smartfunction.md`, prompt, templates |
| A `_CONTEXT_DATA_` key | `jsonata_prompt.txt` (correct **direction** section), `metadata.md`, `smartfunction.md` |
| An enum (`API`, `RepairStrategy`, `CumulocityType`, …) | UI enum in `mapping.model.ts`, both prompts, `jsonata.md` |
| A role name or `@PreAuthorize` | `access-control.md`, the 403 message text, `openapi.json` |
| An `@PreAuthorize`-free endpoint | Ask whether it *should* be guarded — see [access-control.md](../dynamic-mapper-ui/public/docs/access-control.md) |

---

## 5. Pitfalls that have produced wrong conclusions

Every one of these caused a false finding during a real audit. They are listed because the *method*
of checking is as likely to be wrong as the thing being checked.

| Pitfall | Example | Guard |
|---|---|---|
| Regex misses the last enum constant | `CREATE_IF_MISSING` before `}` has no trailing `,` — reported as "missing from Java" when it exists | Match `[,;)}]`, or count against the file |
| Markdown-only image grep | `docs/**` uses `<img src="…">`, not `![](…)` — 46 references invisible | Match both forms |
| Splitting on a heading that occurs twice | Splitting the prompt on "For OUTBOUND mappings" hit the wrong occurrence and reported 8 keys missing that were present | Use `index(start)` then `index(end, start)` |
| Interface ≠ runtime object | `DataPrepContext` 13 methods vs `SmartFunctionContext` 15 | Audit the concrete class that is passed to the engine |
| A green build ≠ the test ran | Two jest suites failed to **compile**, so 17 tests never executed while the summary said "7 passed" | Compare suite *and* test counts before/after |
| `tsc -p tsconfig.json` ≠ what ts-jest compiles | The types spec is outside the main tsconfig; 8 errors were invisible to `tsc` | Run both |
| zsh eats unquoted globs | `grep --include=*.ts` fails with "no matches found" before grep runs | Quote them, or use `grep -r` over a path |

---

## 6. Recommended automation, in priority order

1. **Java reflection contract test** (highest value, lowest cost). A JUnit test that enumerates
   `SmartFunctionContext`'s public methods against a canonical list. Adding a Java method without
   updating the list fails CI, which forces the conversation about the other surfaces. Sketch in
   [smart-function-type-sync.md §5.2](smart-function-type-sync.md).
2. **Prompt contract test.** Assert every `context.*` and `msg.*` token in `prompts/*.txt` exists in
   the runtime list. Cheapest possible protection for the highest-blast-radius surface.
3. **Template lint.** Assert templates call only real APIs, and use field style (`msg.payload`) not
   getter style.
4. **Editor provider test.** Already exists —
   `dynamic-mapper-ui/src/shared/mapping/stepper.model.completion.spec.ts` drives the real provider
   with a stub Monaco. Extend it to assert the advertised member list matches a canonical set.
5. **Doc link + anchor check.** Validate `public/docs/*.md` cross-page links and `{#anchors}` resolve.

Until 1–3 exist, run §3 by hand whenever a change touches `processor/model/` or
`processor/runtime/`.

---

## 7. Audit log

| Date | Scope | Outcome |
|---|---|---|
| 2026-06 | TS types ↔ Java runtime | 6 gaps found and fixed — see [smart-function-type-sync.md §6](smart-function-type-sync.md) |
| 2026-09-19 | All nine surfaces | `CumulocityObject`, `DeviceMessage`, templates, editor provider: **in sync**. Fixed: `OutboundMessage` missing 5 runtime fields; `clearState` missing from TS; 6 context methods undocumented; `jsonata_prompt.txt` missing `deviceFragments`/`deviceGroups`, listing `retain` under the wrong direction, and omitting `OPERATION` from the target-API list |
