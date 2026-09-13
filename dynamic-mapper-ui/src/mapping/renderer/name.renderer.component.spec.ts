import { NO_ERRORS_SCHEMA } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CellRendererContext } from '@c8y/ngx-components';
import { NameRendererComponent } from './name.renderer.component';

/**
 * The mapping-name cell in the statistics grid. The interesting case is the catch-all status
 * row: it must be labelled and highlighted based on its stable `identifier`, never on the
 * `name` the backend happens to send — an older microservice, or a status restored from the
 * persisted `d11r_mapping` fragment, still carries the previous "Unspecified" label.
 *
 * (`overrideComponent` drops `CoreModule` the same way as version-badge.renderer.component.spec.ts:
 * it eagerly reaches into the c8y app-shell DI graph the test injector does not provide.)
 */
describe('NameRendererComponent', () => {
  beforeEach(() => {
    TestBed.overrideComponent(NameRendererComponent, {
      set: { imports: [], schemas: [NO_ERRORS_SCHEMA] }
    });
  });

  function render(value: string, item: Record<string, unknown>) {
    TestBed.configureTestingModule({
      imports: [NameRendererComponent],
      providers: [{ provide: CellRendererContext, useValue: { value, item } }]
    });
    const fixture = TestBed.createComponent(NameRendererComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders a normal mapping under its own name', () => {
    const el = render('Mapping-02', { id: 'mo-1', identifier: 'abc' });

    expect(el.textContent?.trim()).toBe('Mapping-02');
    expect(el.querySelector('span')?.className).toContain('text-normal');
  });

  it('labels the catch-all row from its identifier, ignoring a stale backend name', () => {
    const el = render('Unspecified', { id: 'UNSPECIFIED', identifier: 'UNSPECIFIED' });

    expect(el.textContent?.trim()).toBe('Unmapped messages');
  });

  it('labels the catch-all row even when the backend sends no name at all', () => {
    const el = render(undefined as unknown as string, {
      id: 'UNSPECIFIED',
      identifier: 'UNSPECIFIED'
    });

    expect(el.textContent?.trim()).toBe('Unmapped messages');
  });

  it('highlights the catch-all row and explains it in a tooltip', () => {
    const el = render('Unspecified', { id: 'UNSPECIFIED', identifier: 'UNSPECIFIED' });
    const span = el.querySelector('span');

    expect(span?.className).toContain('text-bold');
    expect(span?.getAttribute('title')).toContain('matched no mapping');
  });

  it('does not highlight a mapping that merely happens to be named like the catch-all row', () => {
    const el = render('Unmapped messages', { id: 'mo-2', identifier: 'xyz' });

    expect(el.querySelector('span')?.className).toContain('text-normal');
  });
});
