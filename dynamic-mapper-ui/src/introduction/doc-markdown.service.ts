/*
 * Copyright (c) 2025 Cumulocity GmbH
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
 *
 * @authors Christof Strack
 */

import { Injectable } from '@angular/core';
import { Marked, RendererObject, Tokens } from 'marked';
import mermaid from 'mermaid';

export interface RenderedDoc {
  title: string;
  html: string;
}

// mermaid.run() scans for elements matching this selector and replaces their text content with
// an inline SVG. It is initialized once, lazily, the first time a doc page needs it.
let mermaidInitialized = false;
function ensureMermaidInitialized(): void {
  if (mermaidInitialized) return;
  mermaidInitialized = true;
  // flowchart.htmlLabels: false renders node labels as plain SVG <text>/<tspan> instead of
  // HTML wrapped in a <foreignObject>. This is required, not cosmetic: foreignObject content is
  // real DOM in this same document, so it inherits doc-shared.css's broad, !important-heavy
  // element-selector rules (font-family/line-height/etc. meant for prose and code blocks) —
  // that silently shrank the text-measurement width mermaid uses to size nodes, producing
  // absurdly narrow, over-wrapped boxes. Plain SVG text is immune to page CSS. <br/> line
  // breaks inside labels still work in this mode; arbitrary HTML tags like <b>/<i> do not, so
  // diagram sources use plain text instead.
  mermaid.initialize({
    startOnLoad: false,
    theme: 'neutral',
    securityLevel: 'loose',
    flowchart: { htmlLabels: false, wrappingWidth: 360 }
  });
}

// Admonition container syntax: ":::kind [Title]\n...markdown...\n:::"
function admonitionExtension(marked: Marked) {
  return {
    name: 'admonition',
    level: 'block' as const,
    start(src: string): number | undefined {
      const m = src.match(/^:::(info|caution|important)/m);
      return m ? m.index : undefined;
    },
    tokenizer(src: string) {
      const rule = /^:::(info|caution|important)[ \t]*([^\n]*)\n([\s\S]*?)\n:::[ \t]*(?:\n+|$)/;
      const match = rule.exec(src);
      if (!match) return undefined;
      const kind = match[1];
      const title = match[2].trim() || kind.charAt(0).toUpperCase() + kind.slice(1);
      return {
        type: 'admonition',
        raw: match[0],
        kind,
        title,
        text: match[3].trim(),
        tokens: []
      } as unknown as Tokens.Generic;
    },
    renderer(token: any): string {
      const body = marked.parse(token.text, { async: false }) as string;
      return `<div class="admonition ${token.kind}"><div class="title">${token.title}</div><div class="content">${body}</div></div>\n`;
    }
  };
}

// Heading anchors: "### Heading text {#custom-id}" -> <h3 id="custom-id">Heading text</h3>
const HEADING_ID_SOURCE = /^(#{1,6}[ \t]+.*?)[ \t]*\{#([a-zA-Z0-9_-]+)\}[ \t]*$/gm;
const HEADING_ID_RENDERED = /<(h[1-6])>(.*?)\s*<!--anchor:([a-zA-Z0-9_-]+)-->\s*<\/\1>/g;

function withHeadingIdMarkers(src: string): string {
  return src.replace(HEADING_ID_SOURCE, '$1 <!--anchor:$2-->');
}

function applyHeadingIds(html: string): string {
  return html.replace(HEADING_ID_RENDERED, '<$1 id="$3">$2</$1>');
}

// A ```mermaid fenced code block is rendered as a bare <pre class="mermaid"> holding the
// escaped source text. mermaid.run() (invoked by DocMarkdownService.renderMermaidDiagrams(),
// called by DocPageComponent/DocOverviewComponent once the HTML is in the DOM) finds elements
// matching that class and replaces their content with an inline SVG. HTML-escaping here is
// still required despite the <pre>: without it, a literal "<br/>" inside a node label (used
// throughout our diagrams for multi-line labels) would be parsed as a real <br> element,
// splitting the text node — element.textContent would then silently drop the tag instead of
// preserving it, corrupting the diagram source mermaid receives.
function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// Images carry a visible caption in the title attribute (alt stays the short a11y label),
// matching the "<img> + .image-description <p>" pairing every doc page used previously.
const docRenderer: Partial<RendererObject> = {
  code({ text, lang }) {
    if (lang !== 'mermaid') return false; // false = fall back to marked's default <pre><code> rendering
    return `<pre class="mermaid">${escapeHtml(text)}</pre>\n`;
  },
  link({ href, title, tokens }) {
    const text = this.parser.parseInline(tokens);
    const titleAttr = title ? ` title="${title}"` : '';
    const external = /^https?:\/\//.test(href);
    const target = external ? ' target="_blank" rel="noopener"' : '';
    return `<a href="${href}"${titleAttr}${target}>${text}</a>`;
  },
  image({ href, title, text }) {
    const img = `<img src="${href}" alt="${text}">`;
    if (!title) return img;
    // Marked wraps a lone inline image in a <p> (it's still an inline token); the caption
    // <p> below is unwrapped from that in unwrapImageCaptions() since a <p> can't nest a <p>.
    return `${img}\n<p class="image-description"><b>Description:</b> ${title}</p>`;
  }
};

// A captioned image renders as <p><img>\n<p class="image-description">...</p></p> because
// marked wraps the sole inline image token in its enclosing paragraph. Drop that outer <p>.
const IMAGE_CAPTION_WRAPPER = /<p>(<img[^>]*>)\n(<p class="image-description">[\s\S]*?<\/p>)<\/p>/g;

function unwrapImageCaptions(html: string): string {
  return html.replace(IMAGE_CAPTION_WRAPPER, '$1\n$2');
}

// marked emits bare <table> elements with no styling hooks. doc-shared.css already defines
// borders/padding/striping under the .table/.table-striped classes (used by hand-written doc
// pages elsewhere), so apply them here too instead of duplicating the rules, and wrap in
// .table-responsive so wide tables scroll horizontally instead of overflowing the page.
function styleTables(html: string): string {
  return html
    .replace(/<table>/g, '<div class="table-responsive"><table class="table table-striped">')
    .replace(/<\/table>/g, '</table></div>');
}

@Injectable({ providedIn: 'root' })
export class DocMarkdownService {
  private marked: Marked;

  constructor() {
    this.marked = new Marked();
    this.marked.use({ extensions: [admonitionExtension(this.marked)], renderer: docRenderer });
  }

  async loadAndRender(docPath: string): Promise<RenderedDoc> {
    // Resolve against the app's own base URL (not the domain root) — the plugin can be
    // mounted under different context paths (e.g. a "-dev" suffix in local dev vs. the
    // real contextPath in production), and this bundled asset always sits alongside it.
    const url = new URL(`docs/${docPath}.md`, document.baseURI);
    const response = await fetch(url);
    if (!response.ok) {
      throw new Error(`Failed to load documentation page "${docPath}" (${response.status})`);
    }
    return this.render(await response.text());
  }

  render(raw: string): RenderedDoc {
    const { frontMatter, body } = this.extractFrontMatter(raw);
    const withMarkers = withHeadingIdMarkers(body);
    const rawHtml = this.marked.parse(withMarkers, { async: false }) as string;
    const html = styleTables(unwrapImageCaptions(applyHeadingIds(rawHtml)));
    return { title: frontMatter['title'] || '', html };
  }

  /**
   * Renders every not-yet-processed `<pre class="mermaid">` element under `container` into an
   * inline SVG diagram. Call this once the rendered HTML from {@link render}/{@link loadAndRender}
   * has actually been committed to the DOM (e.g. after Angular's next change-detection pass) —
   * mermaid.run() operates on real DOM elements, not on an HTML string.
   */
  async renderMermaidDiagrams(container: HTMLElement): Promise<void> {
    const nodes = container.querySelectorAll<HTMLElement>('pre.mermaid:not([data-processed])');
    if (nodes.length === 0) return;
    ensureMermaidInitialized();
    try {
      await mermaid.run({ nodes: Array.from(nodes) });
    } catch (error) {
      // A malformed diagram must not take down the rest of the page; mermaid.run() already
      // renders an inline error SVG into the offending node, so just log for diagnostics.
      console.error('Failed to render one or more mermaid diagrams', error);
    }
  }

  private extractFrontMatter(raw: string): { frontMatter: Record<string, string>; body: string } {
    const match = /^---\n([\s\S]*?)\n---\n?([\s\S]*)$/.exec(raw);
    if (!match) return { frontMatter: {}, body: raw };
    const frontMatter: Record<string, string> = {};
    for (const line of match[1].split('\n')) {
      const kv = /^([a-zA-Z0-9_]+):\s*(.*)$/.exec(line);
      if (kv) frontMatter[kv[1]] = kv[2].trim();
    }
    return { frontMatter, body: match[2] };
  }
}
