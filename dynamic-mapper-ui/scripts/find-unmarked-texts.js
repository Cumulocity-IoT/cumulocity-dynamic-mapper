#!/usr/bin/env node
/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Lists the UI texts that translation extraction cannot see.
 *
 * `extract-i18n` finds only texts marked with the `translate` pipe, the `translate` directive, or
 * `gettext()`. This script reads the templates with the Angular template parser and the
 * TypeScript sources with the TypeScript compiler API, and reports texts that reach the user
 * without one of those marks.
 *
 * Usage (from dynamic-mapper-ui):
 *   node scripts/find-unmarked-texts.js            list the findings, grouped by file
 *   node scripts/find-unmarked-texts.js --json     the same as JSON
 *   node scripts/find-unmarked-texts.js --check    exit with code 1 if there are findings
 *
 * A text that must stay as it is goes into scripts/unmarked-texts.allowlist.json as
 * { "file": "src/...", "text": "...", "reason": "..." }. "text" is a part of the finding's text,
 * "kind" a part of its kind; leave both out to allow a whole file. Give every entry a reason.
 */
const fs = require('fs');
const path = require('path');
const ts = require('typescript');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'src');
const ALLOWLIST = path.join(__dirname, 'unmarked-texts.allowlist.json');

/** Attributes whose value is shown to the user. */
const TEXT_ATTRIBUTES = new Set([
  'label', 'labels', 'title', 'placeholder', 'aria-label', 'alt', 'tooltip', 'header',
  'subtitle', 'text', 'heading', 'message', 'description', 'hint'
]);
/** Properties of object literals in TypeScript whose value is shown to the user. */
const TEXT_PROPERTIES = new Set([
  'label', 'description', 'placeholder', 'tooltip', 'header', 'title', 'subtitle', 'hint',
  'message', 'warning', 'text', 'helpText'
]);
/** Variables that hold lookup tables of texts. */
const TEXT_TABLE_NAME = /(Labels?|Descriptions?|Messages?|Hints?|Texts?|Titles?)$/;
const ALERT_METHODS = new Set(['success', 'danger', 'warning', 'info']);
/** Elements whose text is code, not prose. */
const SKIPPED_ELEMENTS = new Set(['code', 'pre', 'style', 'script']);
const IGNORED_PATH = /(\/generated\/|\.spec\.ts$|\/node_modules\/)/;

function walk(dir, ext, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, ext, out);
    else if (entry.name.endsWith(ext) && !IGNORED_PATH.test(full)) out.push(full);
  }
  return out;
}

const stripEntities = (s) => s.replace(/&[a-zA-Z]+;|&#\d+;/g, ' ');
const hasLetters = (s) => /[A-Za-z]{2,}/.test(stripEntities(s));
/** Identifier-like, constant-like, or path-like strings are not prose. */
function isTechnical(s) {
  const t = s.trim();
  return !hasLetters(t)
    || /^[a-z][a-z0-9_.\-/:#]*$/.test(t)
    || /^[A-Z0-9_ ./-]+$/.test(t)
    || /^https?:/.test(t);
}

const relative = (file) => path.relative(ROOT, file).split(path.sep).join('/');
const findings = [];
function report(file, line, kind, text) {
  findings.push({ file: relative(file), line, kind, text: text.replace(/\s+/g, ' ').trim().slice(0, 140) });
}

// ─── Templates ────────────────────────────────────────────────────────────────

async function scanTemplates(files) {
  const c = await import('@angular/compiler');

  /** Reports string literals in an expression that are not inside `| translate`. */
  class LiteralFinder extends c.RecursiveAstVisitor {
    constructor(onLiteral) {
      super();
      this.onLiteral = onLiteral;
    }
    visitPipe(ast, context) {
      if (ast.name === 'translate') return; // marked: the literal is the message
      return super.visitPipe(ast, context);
    }
    visitLiteralPrimitive(ast) {
      if (typeof ast.value === 'string' && !isTechnical(ast.value)) this.onLiteral(ast.value);
    }
  }

  for (const file of files) {
    const source = fs.readFileSync(file, 'utf8');
    const parsed = c.parseTemplate(source, file, { preserveWhitespaces: false });
    const lineOf = (span) => (span ? span.start.line + 1 : 0);
    const stack = []; // elements and templates above the current node

    const inTranslated = () => stack.some((n) => n.translated);
    const inSkipped = () => stack.some((n) => SKIPPED_ELEMENTS.has(n.name));

    const checkAttributes = (node) => {
      for (const attr of node.attributes ?? []) {
        if (TEXT_ATTRIBUTES.has(attr.name) && !isTechnical(attr.value)) {
          report(file, lineOf(attr.sourceSpan), 'attribute', `${attr.name}="${attr.value}"`);
        }
      }
      for (const input of node.inputs ?? []) {
        if (!TEXT_ATTRIBUTES.has(input.name)) continue;
        const ast = input.value.ast ?? input.value;
        const line = lineOf(input.sourceSpan);
        if (ast instanceof c.Interpolation) {
          ast.strings.forEach((s) => {
            if (hasLetters(s)) report(file, line, 'attribute', `${input.name}="${s.trim()}…"`);
          });
          ast.expressions.forEach((e) =>
            e.visit(new LiteralFinder((v) => report(file, line, 'attribute-literal', `[${input.name}] ${v}`))));
        } else {
          ast.visit(new LiteralFinder((v) => report(file, line, 'attribute-literal', `[${input.name}] ${v}`)));
        }
      }
    };

    class Visitor extends c.TmplAstRecursiveVisitor {
      visitElement(el) {
        const node = {
          name: el.name,
          translated: (el.attributes ?? []).some((a) => a.name === 'translate' || a.name === 'i18n')
        };
        checkAttributes(el);
        stack.push(node);
        super.visitElement(el);
        stack.pop();
      }
      visitTemplate(t) {
        const node = {
          name: t.tagName ?? 'ng-template',
          translated: (t.attributes ?? []).some((a) => a.name === 'translate')
        };
        if (t.tagName === null) checkAttributes(t); // ng-template itself; real elements are visited below
        stack.push(node);
        super.visitTemplate(t);
        stack.pop();
      }
      visitText(text) {
        if (!inTranslated() && !inSkipped() && hasLetters(text.value)) {
          report(file, lineOf(text.sourceSpan), 'text', stripEntities(text.value));
        }
      }
      visitBoundText(text) {
        if (inTranslated() || inSkipped()) return;
        const ast = text.value.ast ?? text.value;
        const line = lineOf(text.sourceSpan);
        if (ast instanceof c.Interpolation) {
          ast.strings.forEach((s) => {
            if (hasLetters(s)) report(file, line, 'text', stripEntities(s));
          });
          ast.expressions.forEach((e) =>
            e.visit(new LiteralFinder((v) => report(file, line, 'text-literal', v))));
        }
      }
    }
    c.tmplAstVisitAll(new Visitor(), parsed.nodes);
  }
}

// ─── TypeScript ───────────────────────────────────────────────────────────────

function scanTypeScript(files) {
  for (const file of files) {
    const source = fs.readFileSync(file, 'utf8');
    const sf = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true);
    const lineOf = (node) => sf.getLineAndCharacterOfPosition(node.getStart()).line + 1;
    const isString = (n) => ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n);

    /** String pieces of an expression that reach the user unmarked. Calls are marks or opaque. */
    function literalsIn(node, out = []) {
      if (isString(node)) {
        if (!isTechnical(node.text)) out.push(node);
      } else if (ts.isTemplateExpression(node)) {
        const text = node.head.text + node.templateSpans.map((s) => '{}' + s.literal.text).join('');
        if (!isTechnical(text)) out.push(node);
      } else if (ts.isConditionalExpression(node)) {
        literalsIn(node.whenTrue, out);
        literalsIn(node.whenFalse, out);
      } else if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.PlusToken) {
        literalsIn(node.left, out);
        literalsIn(node.right, out);
      } else if (ts.isParenthesizedExpression(node) || ts.isAsExpression(node)) {
        literalsIn(node.expression, out);
      }
      return out;
    }
    const textOf = (n) => (isString(n) ? n.text : n.getText().slice(0, 100));
    const flag = (n, kind) => report(file, lineOf(n), kind, textOf(n));

    function lookupTable(node, out) {
      if (ts.isObjectLiteralExpression(node)) {
        node.properties.forEach((p) => ts.isPropertyAssignment(p) && lookupTable(p.initializer, out));
      } else {
        literalsIn(node).forEach((n) => out.push(n));
      }
    }

    function visit(node) {
      // alertService.success('…')  and  alertService.add({ text: '…' })
      if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression)) {
        const method = node.expression.name.text;
        const receiver = node.expression.expression.getText();
        if (/alert/i.test(receiver) && node.arguments.length) {
          if (ALERT_METHODS.has(method)) literalsIn(node.arguments[0]).forEach((n) => flag(n, 'alert'));
        }
      }
      // gettext(`… ${x} …`): the extractor cannot read a placeholder written as ${}
      if (ts.isCallExpression(node) && node.expression.getText() === 'gettext'
          && node.arguments[0] && ts.isTemplateExpression(node.arguments[0])) {
        flag(node.arguments[0], 'gettext-interpolation');
      }
      // this.errorMessage = `…`;  this.statusText = '…';
      if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.EqualsToken
          && ts.isPropertyAccessExpression(node.left) && /(Message|Text|Warning)$/.test(node.left.name.text)) {
        literalsIn(node.right).forEach((n) => flag(n, `assignment ${node.left.name.text}`));
      }
      // label: '…', description: '…', placeholder: '…'
      if (ts.isPropertyAssignment(node) && (ts.isIdentifier(node.name) || ts.isStringLiteral(node.name))) {
        const name = node.name.text;
        if (TEXT_PROPERTIES.has(name)) literalsIn(node.initializer).forEach((n) => flag(n, `property ${name}`));
      }
      // const SomethingLabels = { … }
      if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.initializer
          && TEXT_TABLE_NAME.test(node.name.text)) {
        const found = [];
        lookupTable(node.initializer, found);
        found.forEach((n) => flag(n, `table ${node.name.text}`));
      }
      ts.forEachChild(node, visit);
    }
    visit(sf);
  }
}

// ─── Main ─────────────────────────────────────────────────────────────────────

function loadAllowlist() {
  if (!fs.existsSync(ALLOWLIST)) return [];
  return JSON.parse(fs.readFileSync(ALLOWLIST, 'utf8'));
}

async function main() {
  const args = new Set(process.argv.slice(2));
  await scanTemplates(walk(SRC, '.html'));
  scanTypeScript(walk(SRC, '.ts').filter((f) => !f.endsWith('.d.ts')));

  const allowed = loadAllowlist();
  const isAllowed = (f) =>
    allowed.some((a) => a.file === f.file && (!a.text || f.text.includes(a.text)) && (!a.kind || f.kind.includes(a.kind)));
  const result = findings
    .filter((f) => !isAllowed(f))
    // the same text found twice on one line (a template and its sibling) counts once
    .filter((f, i, all) => all.findIndex((o) => o.file === f.file && o.line === f.line && o.text === f.text) === i)
    .sort((a, b) => a.file.localeCompare(b.file) || a.line - b.line);

  if (args.has('--json')) {
    console.log(JSON.stringify(result, null, 2));
  } else {
    let current = '';
    for (const f of result) {
      if (f.file !== current) {
        current = f.file;
        console.log(`\n${f.file}`);
      }
      console.log(`  ${String(f.line).padStart(4)}  [${f.kind}] ${f.text}`);
    }
    const byKind = {};
    result.forEach((f) => (byKind[f.kind.split(' ')[0]] = (byKind[f.kind.split(' ')[0]] || 0) + 1));
    const files = new Set(result.map((f) => f.file)).size;
    console.log(`\n${result.length} unmarked texts in ${files} files ${JSON.stringify(byKind)}`);
  }
  if (args.has('--check') && result.length) process.exit(1);
}

main().catch((e) => {
  console.error(e);
  process.exit(2);
});
