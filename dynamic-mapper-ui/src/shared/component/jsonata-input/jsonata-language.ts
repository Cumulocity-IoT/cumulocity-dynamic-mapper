/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import type * as Monaco from 'monaco-editor';

export const JSONATA_LANGUAGE_ID = 'jsonata';
export const JSONATA_MARKER_OWNER = 'jsonata';

/** A built-in JSONata function: `signature` is shown in the list, `doc` in the details pane. */
export interface JsonataFunction {
  name: string;
  signature: string;
  doc: string;
}

/** The functions of the JSONata 2.x standard library (https://docs.jsonata.org/). */
export const JSONATA_FUNCTIONS: readonly JsonataFunction[] = [
  // String
  { name: 'string', signature: '$string(arg, prettify?)', doc: 'Casts the argument to a string.' },
  { name: 'length', signature: '$length(str)', doc: 'Number of characters in a string.' },
  { name: 'substring', signature: '$substring(str, start, length?)', doc: 'Substring starting at the zero-based position `start`.' },
  { name: 'substringBefore', signature: '$substringBefore(str, chars)', doc: 'Part of the string before the first occurrence of `chars`.' },
  { name: 'substringAfter', signature: '$substringAfter(str, chars)', doc: 'Part of the string after the first occurrence of `chars`.' },
  { name: 'uppercase', signature: '$uppercase(str)', doc: 'Converts to upper case.' },
  { name: 'lowercase', signature: '$lowercase(str)', doc: 'Converts to lower case.' },
  { name: 'trim', signature: '$trim(str)', doc: 'Removes leading and trailing whitespace and normalizes inner whitespace.' },
  { name: 'pad', signature: '$pad(str, width, char?)', doc: 'Pads the string to `width`. A negative width pads on the left.' },
  { name: 'contains', signature: '$contains(str, pattern)', doc: 'True if the string contains the text or matches the regular expression.' },
  { name: 'split', signature: '$split(str, separator, limit?)', doc: 'Splits a string into an array.' },
  { name: 'join', signature: '$join(array, separator?)', doc: 'Joins an array of strings.' },
  { name: 'match', signature: '$match(str, pattern, limit?)', doc: 'Matches a regular expression and returns the matches with groups.' },
  { name: 'replace', signature: '$replace(str, pattern, replacement, limit?)', doc: 'Replaces text or regular expression matches.' },
  { name: 'eval', signature: '$eval(expr, context?)', doc: 'Evaluates a JSONata expression given as a string.' },
  { name: 'base64encode', signature: '$base64encode(str)', doc: 'Encodes a string as Base64.' },
  { name: 'base64decode', signature: '$base64decode(str)', doc: 'Decodes a Base64 string.' },
  { name: 'encodeUrlComponent', signature: '$encodeUrlComponent(str)', doc: 'Percent-encodes a URL component.' },
  { name: 'encodeUrl', signature: '$encodeUrl(str)', doc: 'Percent-encodes a complete URL.' },
  { name: 'decodeUrlComponent', signature: '$decodeUrlComponent(str)', doc: 'Decodes a URL component.' },
  { name: 'decodeUrl', signature: '$decodeUrl(str)', doc: 'Decodes a complete URL.' },
  // Numeric
  { name: 'number', signature: '$number(arg)', doc: 'Casts the argument to a number.' },
  { name: 'abs', signature: '$abs(number)', doc: 'Absolute value.' },
  { name: 'floor', signature: '$floor(number)', doc: 'Rounds down to an integer.' },
  { name: 'ceil', signature: '$ceil(number)', doc: 'Rounds up to an integer.' },
  { name: 'round', signature: '$round(number, precision?)', doc: 'Rounds to `precision` decimal places.' },
  { name: 'power', signature: '$power(base, exponent)', doc: 'Raises `base` to the power of `exponent`.' },
  { name: 'sqrt', signature: '$sqrt(number)', doc: 'Square root.' },
  { name: 'random', signature: '$random()', doc: 'Pseudo-random number between 0 and 1.' },
  { name: 'formatNumber', signature: '$formatNumber(number, picture, options?)', doc: 'Formats a number with an XPath picture string.' },
  { name: 'formatBase', signature: '$formatBase(number, radix?)', doc: 'Formats a number in the given radix.' },
  { name: 'formatInteger', signature: '$formatInteger(number, picture)', doc: 'Formats an integer, for example as words or roman numerals.' },
  { name: 'parseInteger', signature: '$parseInteger(str, picture)', doc: 'Parses a formatted integer.' },
  // Aggregation
  { name: 'sum', signature: '$sum(array)', doc: 'Sum of an array of numbers.' },
  { name: 'max', signature: '$max(array)', doc: 'Largest number in an array.' },
  { name: 'min', signature: '$min(array)', doc: 'Smallest number in an array.' },
  { name: 'average', signature: '$average(array)', doc: 'Mean of an array of numbers.' },
  // Boolean
  { name: 'boolean', signature: '$boolean(arg)', doc: 'Casts the argument to a boolean.' },
  { name: 'not', signature: '$not(arg)', doc: 'Negates the boolean value of the argument.' },
  { name: 'exists', signature: '$exists(arg)', doc: 'True if the expression evaluates to a value.' },
  // Array
  { name: 'count', signature: '$count(array)', doc: 'Number of items in an array.' },
  { name: 'append', signature: '$append(array, array)', doc: 'Appends two arrays.' },
  { name: 'sort', signature: '$sort(array, function?)', doc: 'Sorts an array, optionally with a comparison function.' },
  { name: 'reverse', signature: '$reverse(array)', doc: 'Reverses the order of an array.' },
  { name: 'shuffle', signature: '$shuffle(array)', doc: 'Randomizes the order of an array.' },
  { name: 'distinct', signature: '$distinct(array)', doc: 'Removes duplicate values.' },
  { name: 'zip', signature: '$zip(array, ...)', doc: 'Combines arrays element by element.' },
  // Object
  { name: 'keys', signature: '$keys(object)', doc: 'Array of the keys of an object.' },
  { name: 'lookup', signature: '$lookup(object, key)', doc: 'Value of a key in an object.' },
  { name: 'spread', signature: '$spread(object)', doc: 'Splits an object into an array of single-key objects.' },
  { name: 'merge', signature: '$merge(array)', doc: 'Merges an array of objects into one.' },
  { name: 'sift', signature: '$sift(object, function)', doc: 'Keeps the key/value pairs for which the function returns true.' },
  { name: 'each', signature: '$each(object, function)', doc: 'Applies a function to every key/value pair.' },
  { name: 'error', signature: '$error(message?)', doc: 'Stops the evaluation with an error.' },
  { name: 'assert', signature: '$assert(condition, message?)', doc: 'Stops the evaluation with an error if the condition is false.' },
  { name: 'type', signature: '$type(value)', doc: 'Type of the value as a string.' },
  // Higher-order
  { name: 'map', signature: '$map(array, function)', doc: 'Applies a function to each item.' },
  { name: 'filter', signature: '$filter(array, function)', doc: 'Keeps the items for which the function returns true.' },
  { name: 'reduce', signature: '$reduce(array, function, init?)', doc: 'Folds an array into a single value.' },
  { name: 'single', signature: '$single(array, function?)', doc: 'The only item matching the function. Fails on zero or several matches.' },
  // Date and time
  { name: 'now', signature: '$now(picture?, timezone?)', doc: 'Current time as an ISO 8601 string.' },
  { name: 'millis', signature: '$millis()', doc: 'Current time in milliseconds since the Unix epoch.' },
  { name: 'fromMillis', signature: '$fromMillis(number, picture?, timezone?)', doc: 'Converts epoch milliseconds to an ISO 8601 string.' },
  { name: 'toMillis', signature: '$toMillis(timestamp, picture?)', doc: 'Converts an ISO 8601 string to epoch milliseconds.' }
];

/** Keywords and the context variables `$`, `$$`, and `$~>` are not functions, so they are listed apart. */
export const JSONATA_KEYWORDS: readonly string[] = ['and', 'or', 'in', 'function', 'true', 'false', 'null'];

/** Monarch grammar for JSONata. Comments are `/* ... *\/`; strings use single or double quotes. */
export const JSONATA_MONARCH: Monaco.languages.IMonarchLanguage = {
  keywords: [...JSONATA_KEYWORDS],
  tokenizer: {
    root: [
      [/\/\*/, 'comment', '@comment'],
      [/\$[A-Za-z_][\w]*/, { cases: { '@default': 'predefined' } }],
      [/\$\$|\$/, 'variable.predefined'],
      [/`[^`]*`/, 'identifier'],
      [/[A-Za-z_][\w]*/, { cases: { '@keywords': 'keyword', '@default': 'identifier' } }],
      [/\d+(\.\d+)?([eE][+-]?\d+)?/, 'number'],
      [/"([^"\\]|\\.)*$/, 'string.invalid'],
      [/'([^'\\]|\\.)*$/, 'string.invalid'],
      [/"/, 'string', '@dq'],
      [/'/, 'string', '@sq'],
      [/~>|\?:|\?\?|\.\.|:=|!=|<=|>=|[=<>+\-*\/%&|^?:.,;@#]/, 'operator'],
      [/[()[\]{}]/, '@brackets']
    ],
    comment: [
      [/[^/*]+/, 'comment'],
      [/\*\//, 'comment', '@pop'],
      [/[/*]/, 'comment']
    ],
    dq: [
      [/[^\\"]+/, 'string'],
      [/\\./, 'string.escape'],
      [/"/, 'string', '@pop']
    ],
    sq: [
      [/[^\\']+/, 'string'],
      [/\\./, 'string.escape'],
      [/'/, 'string', '@pop']
    ]
  }
};

export const JSONATA_LANGUAGE_CONFIGURATION: Monaco.languages.LanguageConfiguration = {
  comments: { blockComment: ['/*', '*/'] },
  brackets: [['(', ')'], ['[', ']'], ['{', '}']],
  autoClosingPairs: [
    { open: '(', close: ')' },
    { open: '[', close: ']' },
    { open: '{', close: '}' },
    { open: '"', close: '"', notIn: ['string'] },
    { open: "'", close: "'", notIn: ['string'] }
  ],
  surroundingPairs: [
    { open: '(', close: ')' },
    { open: '[', close: ']' },
    { open: '{', close: '}' },
    { open: '"', close: '"' },
    { open: "'", close: "'" }
  ],
  // `$` belongs to the word so that completion replaces `$subs` as a whole.
  wordPattern: /\$?[A-Za-z_][\w]*|\$\$?/
};

/** Error shape of the `jsonata` parser: `position` is the zero-based offset right after the offending token. */
export interface JsonataParseError {
  message?: string;
  position?: number;
  token?: string;
  code?: string;
}

/**
 * Parses `expression`. Returns `undefined` for a valid or empty expression, and the parser's error otherwise.
 * `parse` is injected so that the caller decides how `jsonata` is loaded and so tests need no Monaco.
 */
export function findParseError(
  parse: (expression: string) => unknown,
  expression: string
): JsonataParseError | undefined {
  if (!expression?.trim()) {
    return undefined;
  }
  try {
    parse(expression);
    return undefined;
  } catch (error) {
    return error as JsonataParseError;
  }
}

/**
 * Converts a parse error into a marker. The parser reports the offset right after the offending
 * token, so the marker covers the `token` that ends there; without a token it covers one character.
 */
export function toMarker(
  error: JsonataParseError,
  expression: string,
  severity: number
): Monaco.editor.IMarkerData {
  const length = expression.length;
  const end = Math.min(Math.max(error.position ?? length, 0), length);
  const tokenLength = error.token && error.token !== '(end)' ? error.token.length : 1;
  const start = Math.max(0, Math.min(end - tokenLength, Math.max(length - 1, 0)));
  return {
    severity,
    message: error.message ?? 'Invalid JSONata expression',
    // Single-line editor: everything is on line 1.
    startLineNumber: 1,
    endLineNumber: 1,
    startColumn: start + 1,
    endColumn: Math.max(end, start + 1) + 1
  };
}

/**
 * Names of the properties offered after typing a path prefix, derived from the source or target template.
 * `Account.Pro|` offers the keys of `Account`. Arrays are looked through, as JSONata does.
 */
export function pathSuggestions(template: unknown, textBeforeCursor: string): string[] {
  // Take the trailing dotted path of plain names; stop at anything else (operators, brackets, spaces).
  const match = textBeforeCursor.match(/((?:[A-Za-z_][\w]*\.)*)[A-Za-z_]?[\w]*$/);
  const prefix = match?.[1] ?? '';
  const segments = prefix.split('.').filter(Boolean);
  let nodes: unknown[] = [template];
  for (const segment of segments) {
    nodes = nodes.flatMap((node) => unwrap(node)).flatMap((node) => {
      const value = (node as Record<string, unknown>)?.[segment];
      return value === undefined ? [] : [value];
    });
  }
  const keys = new Set<string>();
  for (const node of nodes.flatMap((n) => unwrap(n))) {
    if (node && typeof node === 'object') {
      Object.keys(node as object).forEach((key) => keys.add(key));
    }
  }
  return [...keys];
}

function unwrap(node: unknown): unknown[] {
  return Array.isArray(node) ? node.flatMap(unwrap) : [node];
}

/** Quotes a key with backticks when it is not a valid JSONata name. */
export function quoteKey(key: string): string {
  return /^[A-Za-z_][\w]*$/.test(key) ? key : '`' + key.replace(/`/g, '') + '`';
}

let registered: Promise<void> | undefined;

/**
 * Registers the language once per Monaco instance: grammar, configuration, and function completion.
 * Path completion is registered per editor by {@link registerPathCompletion}.
 */
export function registerJsonataLanguage(monaco: typeof Monaco): Promise<void> {
  registered ??= Promise.resolve().then(() => {
    if (monaco.languages.getLanguages().some((l) => l.id === JSONATA_LANGUAGE_ID)) {
      return;
    }
    monaco.languages.register({ id: JSONATA_LANGUAGE_ID });
    monaco.languages.setMonarchTokensProvider(JSONATA_LANGUAGE_ID, JSONATA_MONARCH);
    monaco.languages.setLanguageConfiguration(JSONATA_LANGUAGE_ID, JSONATA_LANGUAGE_CONFIGURATION);
    monaco.languages.registerCompletionItemProvider(JSONATA_LANGUAGE_ID, {
      triggerCharacters: ['$'],
      provideCompletionItems: (model, position) => {
        const word = model.getWordUntilPosition(position);
        const range = {
          startLineNumber: position.lineNumber,
          endLineNumber: position.lineNumber,
          startColumn: word.startColumn,
          endColumn: word.endColumn
        };
        const functions = JSONATA_FUNCTIONS.map((fn) => ({
          label: fn.signature,
          filterText: '$' + fn.name,
          kind: monaco.languages.CompletionItemKind.Function,
          detail: fn.signature,
          documentation: fn.doc,
          insertText: '$' + fn.name + '($0)',
          insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
          range
        }));
        const keywords = JSONATA_KEYWORDS.map((keyword) => ({
          label: keyword,
          kind: monaco.languages.CompletionItemKind.Keyword,
          insertText: keyword,
          range
        }));
        return { suggestions: [...functions, ...keywords] };
      }
    });
    monaco.languages.registerHoverProvider(JSONATA_LANGUAGE_ID, {
      provideHover: (model, position) => {
        const word = model.getWordAtPosition(position);
        const fn = word && JSONATA_FUNCTIONS.find((f) => '$' + f.name === word.word);
        return fn
          ? {
              range: new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn),
              contents: [{ value: '`' + fn.signature + '`' }, { value: fn.doc }]
            }
          : undefined;
      }
    });
  });
  return registered;
}

/**
 * Offers the keys of the template as completion for the dotted path before the cursor.
 * `getTemplate` is read on every request, so the suggestions follow the template as it changes.
 * Returns a disposable that removes the provider with its editor.
 */
export function registerPathCompletion(
  monaco: typeof Monaco,
  model: Monaco.editor.ITextModel,
  getTemplate: () => unknown
): Monaco.IDisposable {
  return monaco.languages.registerCompletionItemProvider(JSONATA_LANGUAGE_ID, {
    triggerCharacters: ['.'],
    provideCompletionItems: (m, position) => {
      // One provider is registered per editor but providers are global per language: serve only our model.
      if (m.uri.toString() !== model.uri.toString()) {
        return { suggestions: [] };
      }
      const word = m.getWordUntilPosition(position);
      const before = m.getValueInRange({
        startLineNumber: 1,
        startColumn: 1,
        endLineNumber: position.lineNumber,
        endColumn: word.startColumn
      });
      // A `$`-word is a function or variable, not a path.
      if (/\$$/.test(before)) {
        return { suggestions: [] };
      }
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber: position.lineNumber,
        startColumn: word.startColumn,
        endColumn: word.endColumn
      };
      return {
        suggestions: pathSuggestions(getTemplate(), before).map((key) => ({
          label: key,
          kind: monaco.languages.CompletionItemKind.Field,
          insertText: quoteKey(key),
          range
        }))
      };
    }
  });
}
