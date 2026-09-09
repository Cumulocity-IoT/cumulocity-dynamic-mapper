import { NO_ERRORS_SCHEMA, Pipe, PipeTransform } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CellRendererContext } from '@c8y/ngx-components';
import { VersionStateCellRendererComponent } from './version-state-cell.renderer.component';

/**
 * Pure presentational cell renderer for the "State" column of the versions grid.
 * Like VersionBadgeRendererComponent, its template imports CoreModule (for the
 * `translate` pipe used on the label text), which pulls in the c8y app-shell DI graph
 * (ApplicationService) unavailable in the test injector (NG0201). We drop CoreModule via
 * overrideComponent, but the template still references the `translate` pipe by name
 * (NG0302 if nothing provides it — NO_ERRORS_SCHEMA only tolerates unknown
 * elements/attributes, not unknown pipes), so we substitute a trivial identity pipe of
 * the same name and assert on the (untranslated) label text plus the `data-cy` hook and
 * CSS class.
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

  function render(value: string) {
    TestBed.configureTestingModule({
      imports: [VersionStateCellRendererComponent],
      providers: [{ provide: CellRendererContext, useValue: { value } }]
    });
    const fixture = TestBed.createComponent(VersionStateCellRendererComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders the active label with the primary style for "active"', () => {
    const el = render('active');
    const span = el.querySelector('[data-cy="dm-version-state-active"]');
    expect(span).toBeTruthy();
    expect(span?.className).toContain('label-primary');
    expect(span?.textContent).toContain('active');
  });

  it('renders the draft label with the info style for "draft"', () => {
    const el = render('draft');
    const span = el.querySelector('[data-cy="dm-version-state-draft"]');
    expect(span).toBeTruthy();
    expect(span?.className).toContain('label-info');
    expect(span?.textContent).toContain('draft');
  });

  it('falls back to the published label with the default style for any other value', () => {
    const el = render('published');
    const span = el.querySelector('[data-cy="dm-version-state-published"]');
    expect(span).toBeTruthy();
    expect(span?.className).toContain('label-default');
    expect(span?.textContent).toContain('published');
  });
});
