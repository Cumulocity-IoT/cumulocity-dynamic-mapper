#!/usr/bin/env node
/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Writes the app's own translation catalogs, src/locales/<language>.po.
 *
 * `extract-i18n` writes locales/locales.pot with every message the build sees, including the
 * platform's, and merges it into locales/<language>.po with the platform translations. The app
 * must not ship those again, so this script keeps only the messages the platform catalog does
 * not contain, and keeps translations that already exist in src/locales/<language>.po.
 *
 * Usage (from dynamic-mapper-ui, after `npx ng run dynamic-mapper:extract-i18n`):
 *   node scripts/sync-app-catalogs.js            update the catalogs of the languages below
 *   node scripts/sync-app-catalogs.js --check    exit 1 if a catalog is out of date
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const POT = path.join(ROOT, 'locales', 'locales.pot');
const PLATFORM = (lang) => path.join(ROOT, 'node_modules/@c8y/ngx-components/locales', `${lang}.po`);
const OUT = (lang) => path.join(ROOT, 'src', 'locales', `${lang}.po`);

/** Languages the app ships. Add a language here, run the script, and import the file in src/i18n.ts. */
const LANGUAGES = [{ code: 'de', team: 'German', plural: 'nplurals=2; plural=(n != 1);' }];

const unquote = (lines) => lines.map((l) => JSON.parse(l.trim())).join('');
const quote = (s) => {
  const parts = s.split('\n');
  const q = (p) => JSON.stringify(p);
  if (parts.length === 1) return `${q(s)}`;
  return `""\n` + parts.map((p, i) => q(i < parts.length - 1 ? p + '\n' : p)).filter((x) => x !== '""').join('\n');
};

/** Reads a .po or .pot file into [{ id, str, comments }] (header excluded). Plurals are not used. */
function parse(file) {
  if (!fs.existsSync(file)) return [];
  const entries = [];
  for (const block of fs.readFileSync(file, 'utf8').split(/\n\s*\n/)) {
    const lines = block.split('\n');
    const comments = lines.filter((l) => l.startsWith('#'));
    const idAt = lines.findIndex((l) => l.startsWith('msgid '));
    const strAt = lines.findIndex((l) => l.startsWith('msgstr '));
    if (idAt < 0 || strAt < 0) continue;
    const id = unquote([lines[idAt].slice(6), ...lines.slice(idAt + 1, strAt).filter((l) => l.startsWith('"'))]);
    const str = unquote([lines[strAt].slice(7), ...lines.slice(strAt + 1).filter((l) => l.startsWith('"'))]);
    if (id) entries.push({ id, str, comments });
  }
  return entries;
}

function render(lang, entries) {
  const header = [
    'msgid ""', 'msgstr ""',
    '"Project-Id-Version: dynamic-mapper\\n"',
    `"Language: ${lang.code}\\n"`,
    `"Language-Team: ${lang.team}\\n"`,
    '"Content-Type: text/plain; charset=UTF-8\\n"',
    '"Content-Transfer-Encoding: 8bit\\n"',
    `"Plural-Forms: ${lang.plural}\\n"`
  ].join('\n');
  const body = entries.map((e) => `msgid ${quote(e.id)}\nmsgstr ${quote(e.str)}`).join('\n\n');
  return `${header}\n\n${body}\n`;
}

function main() {
  const check = process.argv.includes('--check');
  const pot = parse(POT);
  let stale = false;
  for (const lang of LANGUAGES) {
    const platform = new Set(parse(PLATFORM(lang.code)).map((e) => e.id));
    const existing = new Map(parse(OUT(lang.code)).map((e) => [e.id, e.str]));
    const entries = pot
      .filter((e) => !platform.has(e.id))
      .map((e) => ({ id: e.id, str: existing.get(e.id) ?? '' }))
      .sort((a, b) => a.id.localeCompare(b.id));
    const text = render(lang, entries);
    const current = fs.existsSync(OUT(lang.code)) ? fs.readFileSync(OUT(lang.code), 'utf8') : '';
    const missing = entries.filter((e) => !e.str).length;
    if (check) {
      if (current !== text) stale = true;
    } else {
      fs.mkdirSync(path.dirname(OUT(lang.code)), { recursive: true });
      fs.writeFileSync(OUT(lang.code), text);
    }
    console.log(`${lang.code}: ${entries.length} messages, ${entries.length - missing} translated, ${missing} untranslated`);
  }
  if (check && stale) {
    console.error('src/locales is out of date: run extract-i18n and scripts/sync-app-catalogs.js');
    process.exit(1);
  }
}
main();
