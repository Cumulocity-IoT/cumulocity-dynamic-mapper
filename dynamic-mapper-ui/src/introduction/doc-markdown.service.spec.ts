/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

import { DocMarkdownService } from './doc-markdown.service';

describe('DocMarkdownService tables', () => {
  let service: DocMarkdownService;

  beforeEach(() => {
    service = new DocMarkdownService();
  });

  const TABLE = '| QoS | A | B |\n|:---:|---|---|\n| 0 | x | y |';

  it('styles every Markdown table', () => {
    const { html } = service.render(TABLE);

    expect(html).toContain('<div class="table-responsive"><table class="table table-striped">');
    expect(html).not.toContain('table-equal-columns');
  });

  it('gives a table preceded by the marker the equal-columns class and leaves no comment behind', () => {
    const { html } = service.render(`<!--table:equal-columns-->\n\n${TABLE}`);

    expect(html).toContain('<table class="table table-striped table-equal-columns">');
    expect(html).not.toContain('table:equal-columns');
  });

  it('applies the marker to the table that follows it, not to the others', () => {
    const { html } = service.render(`<!--table:equal-columns-->\n\n${TABLE}\n\nText\n\n${TABLE}`);

    expect(html.match(/table-equal-columns/g)?.length).toBe(1);
    expect(html.match(/<table /g)?.length).toBe(2);
  });
});

describe('DocMarkdownService diagrams', () => {
  it('renders the diagram of the reliability page without a syntax error', async () => {
    const service = new DocMarkdownService();
    const { html } = await service.loadAndRender('reliability-settings');
    const container = document.createElement('div');
    container.innerHTML = html;
    document.body.appendChild(container);
    try {
      expect(container.querySelectorAll('pre.mermaid').length).toBeGreaterThan(0);

      await service.renderMermaidDiagrams(container);

      expect(container.querySelectorAll('pre.mermaid svg').length).toBeGreaterThan(0);
      expect(container.textContent).not.toContain('Syntax error');
    } finally {
      container.remove();
    }
  });
});
