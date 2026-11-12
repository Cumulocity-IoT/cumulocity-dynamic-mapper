import { TestBed } from '@angular/core/testing';
import { CellRendererContext } from '@c8y/ngx-components';
import { TranslateModule } from '@ngx-translate/core';
import { ExpandableTextRendererComponent } from './expandable-text.renderer.component';

/**
 * The cell that keeps long text, such as a mapping's last error, to one line with a "More" button. The
 * overflow check measures real layout, so these tests run in the browser with a fixed-width host.
 */
describe('ExpandableTextRendererComponent', () => {
  const LONG = 'Failed to deserialize payload: Failed to deserialize JSON payload: Expected value at 1:1 '.repeat(3);

  function render(value: string, item: Record<string, unknown> = { identifier: 'row-1' }, width = '200px') {
    TestBed.configureTestingModule({
      imports: [ExpandableTextRendererComponent, TranslateModule.forRoot()],
      providers: [{ provide: CellRendererContext, useValue: { value, item, property: { name: 'lastErrorDisplay' } } }]
    });
    const fixture = TestBed.createComponent(ExpandableTextRendererComponent);
    const host = fixture.nativeElement as HTMLElement;
    host.style.display = 'block';
    host.style.width = width;
    document.body.appendChild(host);
    fixture.detectChanges();
    fixture.detectChanges(); // the overflow check runs after the first render
    return { fixture, host };
  }

  const button = (host: HTMLElement) => host.querySelector('button') as HTMLButtonElement | null;

  afterEach(() => {
    document.body.querySelectorAll('d11r-renderer-expandable-text').forEach((e) => e.remove());
  });

  it('shows short text without a button', () => {
    const { host } = render('boom');
    expect(host.textContent).toContain('boom');
    expect(button(host)).toBeNull();
  });

  it('offers More for text that does not fit, and expands and collapses it', () => {
    const { fixture, host } = render(LONG);
    expect(button(host)?.textContent?.trim()).toBe('More');
    expect(host.querySelector('.expanded')).toBeNull();

    button(host)!.click();
    fixture.detectChanges();
    expect(host.querySelector('.expanded')).not.toBeNull();
    expect(button(host)?.textContent?.trim()).toBe('Less');

    button(host)!.click();
    fixture.detectChanges();
    expect(host.querySelector('.expanded')).toBeNull();
  });

  it('keeps a row open when the grid re-creates the cell', () => {
    const first = render(LONG, { identifier: 'row-keep' });
    button(first.host)!.click();
    first.fixture.detectChanges();
    first.fixture.destroy();
    TestBed.resetTestingModule();

    const second = render(LONG, { identifier: 'row-keep' });
    expect(second.host.querySelector('.expanded')).not.toBeNull();

    button(second.host)!.click(); // leave no open row behind for other tests
  });

  it('renders nothing for a missing value', () => {
    const { host } = render(undefined as unknown as string);
    expect(host.querySelector('.d11r-expandable-text__body')?.textContent?.trim()).toBe('');
    expect(button(host)).toBeNull();
  });
});
