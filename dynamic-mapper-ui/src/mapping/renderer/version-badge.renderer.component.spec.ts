import { NO_ERRORS_SCHEMA } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CellRendererContext } from '@c8y/ngx-components';
import { VersionBadgeRendererComponent } from './version-badge.renderer.component';

/**
 * `VersionBadgeRendererComponent` is a pure presentational cell renderer used by the
 * mapping/version-counts grid to show the active version as a badge. Its template
 * imports `CoreModule` (for pipes like `translate`), which eagerly reaches into the
 * c8y app-shell DI graph (`ApplicationService`) that the test injector doesn't provide
 * (NG0201) — see mapping-type-drawer.component.spec.ts for the same issue. Since this
 * component's own template needs no c8y directives/pipes, we drop `CoreModule` via
 * `overrideComponent` while keeping the real template so we can still assert on the
 * rendered badge/dash text.
 */
describe('VersionBadgeRendererComponent', () => {
  beforeEach(() => {
    TestBed.overrideComponent(VersionBadgeRendererComponent, {
      set: { imports: [], schemas: [NO_ERRORS_SCHEMA] }
    });
  });

  function render(value: unknown) {
    TestBed.configureTestingModule({
      imports: [VersionBadgeRendererComponent],
      providers: [{ provide: CellRendererContext, useValue: { value } }]
    });
    const fixture = TestBed.createComponent(VersionBadgeRendererComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders a "vX" badge when a version value is present', () => {
    const el = render('1.2.3');
    expect(el.textContent).toContain('v1.2.3');
  });

  it('renders a dash when the value is falsy (no active version)', () => {
    const el = render(undefined);
    expect(el.textContent?.trim()).toBe('—');
  });

  it('renders a dash when the value is an empty string', () => {
    const el = render('');
    expect(el.textContent?.trim()).toBe('—');
  });
});
