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
