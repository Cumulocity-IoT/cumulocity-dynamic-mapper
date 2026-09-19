#!/usr/bin/env node
/**
 * Generates the mapping editor's completion/hover data from the Smart Function type definitions.
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

/** Collects every exported interface / type alias declaration by name. */
const declarations = new Map();
for (const sf of program.getSourceFiles()) {
  if (sf.isDeclarationFile) continue;
  ts.forEachChild(sf, node => {
    if (ts.isInterfaceDeclaration(node) || ts.isTypeAliasDeclaration(node)) {
      declarations.set(node.name.text, node);
    }
  });
}

const docOf = sym => ts.displayPartsToString(sym.getDocumentationComment(checker)).replace(/\s+/g, ' ').trim();
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

function buildInterface(name) {
  const decl = declarations.get(name);
  if (!decl) throw new Error(`interface ${name} not found in ${ENTRY}`);
  const defaults = typeParamDefaults(decl);
  const type = checker.getTypeAtLocation(decl);

  const properties = [];
  const methods = [];

  for (const sym of checker.getPropertiesOfType(type)) {
    const d = sym.valueDeclaration ?? sym.declarations?.[0];
    if (!d) continue;
    const symType = checker.getTypeOfSymbolAtLocation(sym, d);
    const signatures = symType.getCallSignatures();
    const documentation = docOf(sym);

    if (signatures.length > 0) {
      // Overloads: the last signature is the most general, which is the useful one to show.
      const sig = signatures[signatures.length - 1];
      methods.push({
        name: sym.getName(),
        parameters: sig.getParameters().map(p => p.getName()),
        returnType: renderType(checker.typeToString(sig.getReturnType()), defaults),
        documentation
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

function buildUnion({ as, from }) {
  const decl = declarations.get(from);
  if (!decl) throw new Error(`union ${from} not found in ${ENTRY}`);
  const type = checker.getTypeAtLocation(decl);
  const members = type.isUnion() ? type.types : [type];
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

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, header + JSON.stringify(api, null, 2) + ';\n');

const ifaces = api.filter(e => !e.isEnum).length;
console.log(
  `[generate-editor-api] ${api.length} entries -> ${path.relative(path.resolve(PKG_ROOT, '..'), OUT)} ` +
    `(${ifaces} interfaces, ${api.length - ifaces} enums)`
);
