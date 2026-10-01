#!/usr/bin/env node
/**
 * Generates the mapping editor's completion/hover data from the Smart Function type definitions.
 *
 * HOW IT WORKS, IN ONE PARAGRAPH
 * TypeScript interfaces vanish at compile time, so the editor cannot reflect over them the way
 * the Java tests reflect over the runtime classes. Instead this script runs the TypeScript
 * *compiler* over the type definitions and walks the resulting program: for each interface it
 * asks the type checker for the properties, their resolved types and their JSDoc, and writes all
 * of that out as a plain data array the editor can import at runtime.
 *
 * The editor's provider (dynamic-mapper-ui/src/shared/mapping/stepper.model.ts) used to restate
 * ~188 lines of type information that already exists as TypeScript interfaces here. The two
 * copies drifted silently — nothing could catch a renamed field or a stale enum value. This
 * reads the real declarations with the TypeScript compiler API and emits that table instead, so
 * the editor cannot describe an API the types do not have.
 *
 * The output is committed; CI regenerates and fails on a diff, so the generated file is always
 * reviewable in the PR that changes the types.
 *
 * Run: npm run generate:editor-api
 */
const ts = require('typescript');
const fs = require('fs');
const path = require('path');

const PKG_ROOT = path.resolve(__dirname, '..');
const ENTRY = path.join(PKG_ROOT, 'src/types/smart-function-dynamic-mapper.types.ts');
const OUT = path.resolve(
  PKG_ROOT,
  '../dynamic-mapper-ui/src/shared/mapping/generated/smart-function-api.generated.ts'
);

/**
 * What the editor advertises, and where it comes from.
 *
 * `as` is the name the editor shows when it differs from the TypeScript export — the editor's
 * vocabulary predates these type names and is what users see in existing docs and screenshots,
 * so it is kept rather than renamed underneath them.
 */
const INTERFACES = [
  'CumulocityObject',
  'DeviceMessage',
  'ExternalId',
  'ExternalSource',
  'DataPrepContext',
  'SmartFunctionContext',
  'DynamicMapperDeviceMessage',
  'OutputMessage',
  'MappingError',
  'OutboundMessage'
];

const UNIONS = [
  { as: 'CumulocityAction', from: 'C8yObjectAction' },
  { as: 'CumulocityType', from: 'C8yObjectType' },
  { as: 'Destination', from: 'C8yDestination' },
  { as: 'ChildReference', from: 'C8yChildReference' }
];

// A TypeScript program is the compiler's view of a set of files: parsed, bound, and ready to be
// queried. The checker is what answers semantic questions about it ("what type is this symbol?",
// "what does its JSDoc say?") — the same engine the editor uses for IntelliSense.
const program = ts.createProgram([ENTRY], {
  noEmit: true,
  skipLibCheck: true,
  strict: false,
  target: ts.ScriptTarget.ES2020,
  moduleResolution: ts.ModuleResolutionKind.Bundler
});
const checker = program.getTypeChecker();
const source = program.getSourceFile(ENTRY);
if (!source) throw new Error(`cannot load ${ENTRY}`);

/**
  * Index every interface and type alias by name, across the entry file and everything it imports.
  *
  * Needed because the two inputs are looked up by name (INTERFACES, UNIONS) and because
  * DataPrepContext and ExternalId live in a second file (dataprep.types.ts) that the entry file
  * re-exports. Walking `program.getSourceFiles()` rather than just the entry picks those up.
  * Declaration files (.d.ts) are skipped: lib.dom, node types and the like would flood the map.
  */
const declarations = new Map();
for (const sf of program.getSourceFiles()) {
  if (sf.isDeclarationFile) continue;
  ts.forEachChild(sf, node => {
    if (ts.isInterfaceDeclaration(node) || ts.isTypeAliasDeclaration(node)) {
      declarations.set(node.name.text, node);
    }
  });
}

// The JSDoc prose for a symbol, flattened to a single line. getDocumentationComment returns
// structured "display parts" (text, link targets, …) rather than a string, and drops the tags —
// so `@example` and `@since` blocks do not end up in the tooltip, only the description.
const docOf = sym => ts.displayPartsToString(sym.getDocumentationComment(checker)).replace(/\s+/g, ' ').trim();

// Tags are read separately from the prose. The editor greys out and de-prioritises anything
// carrying @deprecated, so this has to survive the trip from the type definitions.
const isDeprecated = sym => sym.getJsDocTags(checker).some(t => t.name === 'deprecated');

/**
 * Type parameters leak into property types as bare names (`payload: TPayload`), which is
 * meaningless in a completion popup. Substitute each parameter with its declared default, which
 * is what a user writing an unparameterised Smart Function actually gets.
 */
function typeParamDefaults(decl) {
  const map = new Map();
  for (const tp of decl.typeParameters ?? []) {
    map.set(tp.name.text, tp.default ? tp.default.getText(source) : 'any');
  }
  return map;
}

function renderType(typeText, defaults) {
  let out = typeText;
  // Defaults can reference other parameters (`TPayload = C8yReceivedPayloadTypeMap[T]`), so
  // substitute repeatedly until nothing changes rather than in a single pass.
  for (let pass = 0; pass < 5; pass++) {
    const before = out;
    for (const [param, fallback] of defaults) {
      out = out.replace(new RegExp(`\\b${param}\\b`, 'g'), fallback);
    }
    if (out === before) break;
  }
  return out.replace(/\s+/g, ' ').trim();
}

/**
  * Turns one interface into the editor's entry for it.
  *
  * Properties and methods are not declared separately in the table, so they are told apart by
  * asking the checker whether a member's type has call signatures — `getState(key)` does,
  * `payload` does not. That also means a property holding a function type would be listed as a
  * method, which is the right answer for a completion popup.
  */
function buildInterface(name) {
  const decl = declarations.get(name);
  // Fail loudly. A renamed interface silently producing a shorter table would quietly strip
  // entries out of the editor, and the CI diff would look like an intentional change.
  if (!decl) throw new Error(`interface ${name} not found in ${ENTRY}`);
  const defaults = typeParamDefaults(decl);
  const type = checker.getTypeAtLocation(decl);

  const properties = [];
  const methods = [];

  // getPropertiesOfType, not the declaration's own members: this resolves inherited members too,
  // which is how SmartFunctionContext picks up everything it extends from DataPrepContext.
  for (const sym of checker.getPropertiesOfType(type)) {
    const d = sym.valueDeclaration ?? sym.declarations?.[0];
    if (!d) continue;
    const symType = checker.getTypeOfSymbolAtLocation(sym, d);
    const signatures = symType.getCallSignatures();
    const documentation = docOf(sym);

    if (signatures.length > 0) {
      // Overloads: the last signature is the most general, which is the useful one to show.
      const sig = signatures[signatures.length - 1];
      const methodDefaults = new Map(defaults);
      for (const tp of sig.typeParameters ?? []) {
        const typeParamName = tp?.symbol?.escapedName ?? tp?.name?.text ?? tp?.getSymbol?.()?.getName();
        if (typeParamName == null) continue;
        methodDefaults.set(String(typeParamName), tp.default?.getText() ?? 'any');
      }
      // Prefer the chosen overload's own JSDoc; fall back to the symbol's. Always emit the key,
      // even when empty: `ClassDefinition.methods` requires `documentation`, so omitting it makes
      // the generated file fail to compile in the UI build.
      const sigDoc = ts.displayPartsToString(sig.getDocumentationComment(checker))
        .replace(/\s+/g, ' ')
        .trim();
      methods.push({
        name: sym.getName(),
        parameters: sig.getParameters().map(p => p.getName()),
        returnType: renderType(checker.typeToString(sig.getReturnType()), methodDefaults),
        documentation: sigDoc || documentation,
      });
    } else {
      const rendered = renderType(checker.typeToString(symType), defaults);
      // `never` marks a field the runtime deliberately leaves unset for this direction (outbound
      // has no clientId/transportId/transportFields). It is useful in the type — it keeps the
      // interfaces structurally comparable and documents the asymmetry — but offering it in
      // autocomplete would suggest a field that is always undefined.
      if (rendered === 'never') continue;
      properties.push({ name: sym.getName(), type: rendered, documentation });
    }
  }

  const entry = {
    name,
    isEnum: false,
    properties,
    methods,
    documentation: docOf(checker.getSymbolAtLocation(decl.name))
  };
  if (isDeprecated(checker.getSymbolAtLocation(decl.name))) entry.deprecated = true;
  return entry;
}

/**
  * Turns a string-literal union (`'create' | 'update' | …`) into the editor's enum entry.
  *
  * These are unions rather than TypeScript enums because that is what the runtime actually
  * exchanges — plain strings over the GraalVM boundary — so the type mirrors the wire format
  * instead of introducing a construct the JavaScript side does not have.
  */
function buildUnion({ as, from }) {
  const decl = declarations.get(from);
  if (!decl) throw new Error(`union ${from} not found in ${ENTRY}`);
  const type = checker.getTypeAtLocation(decl);
  const members = type.isUnion() ? type.types : [type];
  // Only string literals become values. A union that had drifted to include something else
  // (a widened `string`, say) would silently contribute nothing, so the guard below catches it.
  const values = members
    .map(t => (t.isStringLiteral() ? t.value : null))
    .filter(v => v !== null);
  if (values.length === 0) throw new Error(`${from} has no string literal members`);
  return {
    name: as,
    isEnum: true,
    values,
    documentation: docOf(checker.getSymbolAtLocation(decl.name))
  };
}

const api = [...INTERFACES.map(buildInterface), ...UNIONS.map(buildUnion)];

const header = `/*
 * GENERATED FILE — DO NOT EDIT.
 *
 * Produced by dynamic-mapper-smart-function/scripts/generate-editor-api.cjs from
 * src/types/smart-function-dynamic-mapper.types.ts.
 *
 * To change what the mapping editor's autocomplete and hover show, change the TypeScript type
 * definitions and re-run:
 *
 *     cd dynamic-mapper-smart-function && npm run generate:editor-api
 *
 * CI regenerates this file and fails if the result differs from what is committed.
 */

import type { ClassOrEnum } from '../smart-function-api.model';

/**
 * Typed as ClassOrEnum on purpose: TypeScript then verifies that what this generator produces
 * actually satisfies the shape the completion and hover providers consume. A generator change
 * that dropped \`methods\`, or emitted an enum without \`values\`, fails to compile here rather
 * than silently degrading autocomplete.
 */
export const SMART_FUNCTION_API: ClassOrEnum[] = `;

// Written as formatted JSON inside a TypeScript file: valid TS, and a readable line-by-line
// diff in the pull request that changes the types — which is the point of committing it rather
// than generating during the UI build.
fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, header + JSON.stringify(api, null, 2) + ';\n');

const ifaces = api.filter(e => !e.isEnum).length;
console.log(
  `[generate-editor-api] ${api.length} entries -> ${path.relative(path.resolve(PKG_ROOT, '..'), OUT)} ` +
    `(${ifaces} interfaces, ${api.length - ifaces} enums)`
);
