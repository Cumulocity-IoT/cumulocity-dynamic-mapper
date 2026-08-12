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

export interface RenderedDoc {
  title: string;
  html: string;
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

// Images carry a visible caption in the title attribute (alt stays the short a11y label),
// matching the "<img> + .image-description <p>" pairing every doc page used previously.
const docRenderer: Partial<RendererObject> = {
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
    const html = unwrapImageCaptions(applyHeadingIds(rawHtml));
    return { title: frontMatter['title'] || '', html };
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
