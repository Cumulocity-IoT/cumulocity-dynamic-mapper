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
