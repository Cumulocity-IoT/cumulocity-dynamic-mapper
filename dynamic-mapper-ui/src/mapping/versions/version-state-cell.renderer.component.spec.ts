import { NO_ERRORS_SCHEMA, Pipe, PipeTransform } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CellRendererContext } from '@c8y/ngx-components';
import { VersionStateCellRendererComponent } from './version-state-cell.renderer.component';

/**
 * State column of the versions grid — a plain badge for 'draft', a toggle switch (doubling as
 * the activation control) for 'active'/'published'. Like VersionBadgeRendererComponent, its
 * template imports CoreModule (for the `translate` pipe used on the label text), which pulls in
 * the c8y app-shell DI graph (ApplicationService) unavailable in the test injector (NG0201). We
 * drop CoreModule via overrideComponent, but the template still references the `translate` pipe
 * by name (NG0302 if nothing provides it — NO_ERRORS_SCHEMA only tolerates unknown
 * elements/attributes, not unknown pipes), so we substitute a trivial identity pipe of the same
 * name and assert on the (untranslated) label text plus the `data-cy` hook and CSS class.
 */
@Pipe({ name: 'translate', standalone: true })
class IdentityTranslatePipe implements PipeTransform {
  transform(value: string): string {
    return value;
  }
}

describe('VersionStateCellRendererComponent', () => {
  beforeEach(() => {
    TestBed.overrideComponent(VersionStateCellRendererComponent, {
      set: { imports: [IdentityTranslatePipe], schemas: [NO_ERRORS_SCHEMA] }
    });
  });

  function render(value: string, item: Record<string, unknown> = { id: 'row-1' }) {
    TestBed.configureTestingModule({
      imports: [VersionStateCellRendererComponent],
      providers: [{ provide: CellRendererContext, useValue: { value, item } }]
    });
    const fixture = TestBed.createComponent(VersionStateCellRendererComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('renders the draft label with the warning style, and no toggle', () => {
    // label-warning, not label-info: a draft is unfinished work that needs publishing, and the
    // badge must look the same here as in the mapping grid (StatusRendererComponent). A draft
    // isn't directly activatable (must be published first), so it gets no toggle at all.
    const fixture = render('draft');
    const el = fixture.nativeElement as HTMLElement;
    const span = el.querySelector('[data-cy="dm-version-state-draft"]');
    expect(span).toBeTruthy();
    expect(span?.className).toContain('label-warning');
    expect(span?.textContent).toContain('draft');
    expect(el.querySelector('input[type="checkbox"]')).toBeNull();
  });

  it('renders the active row as an on, disabled toggle', () => {
    const fixture = render('active', { id: 'row-1', onActivate: () => undefined });
    const el = fixture.nativeElement as HTMLElement;
    const input = el.querySelector<HTMLInputElement>('[data-cy="dm-version-state-toggle-row-1"]');
    expect(input).toBeTruthy();
    expect(input?.checked).toBe(true);
    // Disabled even though onActivate is present — this row is already active, nothing to do.
    expect(input?.disabled).toBe(true);
    expect(el.textContent).toContain('active');
  });

  it('renders a published row as an off toggle, clickable to activate it', () => {
    const onActivate = jasmine.createSpy('onActivate');
    const fixture = render('published', { id: 'row-2', onActivate });
    const component = fixture.componentInstance;
    const el = fixture.nativeElement as HTMLElement;
    const input = el.querySelector<HTMLInputElement>('[data-cy="dm-version-state-toggle-row-2"]');
    expect(input).toBeTruthy();
    expect(input?.checked).toBe(false);
    expect(input?.disabled).toBe(false);
    expect(el.textContent).toContain('published');

    component.onToggleClick(new Event('click'));
    expect(onActivate).toHaveBeenCalled();
  });

  it('disables the toggle for a published row when no onActivate callback is provided (canManage=false)', () => {
    const fixture = render('published', { id: 'row-3' });
    const el = fixture.nativeElement as HTMLElement;
    const input = el.querySelector<HTMLInputElement>('[data-cy="dm-version-state-toggle-row-3"]');
    expect(input?.disabled).toBe(true);
  });

  it('does nothing when the active row\'s toggle is clicked despite being visually enabled logic-wise', () => {
    const onActivate = jasmine.createSpy('onActivate');
    const fixture = render('active', { id: 'row-1', onActivate });
    fixture.componentInstance.onToggleClick(new Event('click'));
    expect(onActivate).not.toHaveBeenCalled();
  });
});
