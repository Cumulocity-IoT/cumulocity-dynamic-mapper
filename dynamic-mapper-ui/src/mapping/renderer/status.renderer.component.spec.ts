import { NO_ERRORS_SCHEMA } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CellRendererContext } from '@c8y/ngx-components';
import { StatusRendererComponent } from './status.renderer.component';

/**
 * `StatusRendererComponent` renders the Version/Status cell of the mapping grid in a fixed order:
 * version, then draft, then debug. Only the version is interactive — when the column supplies a
 * `callback` it opens the version drawer; the state labels are status information and must never
 * be links.
 *
 * As in version-badge.renderer.component.spec.ts, `CoreModule` is dropped via `overrideComponent`
 * because it reaches into the c8y app-shell DI graph (`ApplicationService`) that a bare TestBed
 * does not provide (NG0201). The real template is kept so the markup can still be asserted on.
 */
describe('StatusRendererComponent', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.overrideComponent(StatusRendererComponent, {
      set: { imports: [], schemas: [NO_ERRORS_SCHEMA] }
    });
  });

  function render(value: unknown, callback?: (item: unknown) => void) {
    const context = {
      value,
      item: { id: 'row-1' },
      property: callback ? { callback } : {}
    };
    TestBed.configureTestingModule({
      imports: [StatusRendererComponent],
      providers: [{ provide: CellRendererContext, useValue: context }]
    });
    const fixture = TestBed.createComponent(StatusRendererComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const ALL = { version: '1.2.3', debug: true, draftDirty: true };

  // TestBed cannot be reconfigured once instantiated, so each render() needs its own spec.
  function order(el: HTMLElement) {
    return Array.from(el.querySelectorAll('[data-cy^="dm-mapping-status-"]'))
      .map(n => n.getAttribute('data-cy')?.replace('dm-mapping-status-', '').replace('-row-1', ''));
  }

  it('renders version, then draft, then debug', () => {
    // draft describes the version state, so it groups with the version; debug is operational
    // status and comes last.
    expect(order(render(ALL, () => undefined))).toEqual(['link', 'version', 'draft', 'debug']);
  });

  it('keeps that same order when the version is not a link', () => {
    expect(order(render(ALL))).toEqual(['version', 'draft', 'debug']);
  });

  it('renders the version as a plain badge when the column supplies no callback', () => {
    const el = render(ALL);
    expect(el.querySelector('a')).toBeNull();
    expect(el.textContent).toContain('v1.2.3');
    expect(el.textContent).toContain('debug');
    expect(el.textContent).toContain('draft');
  });

  it('makes only the version a link when the column supplies a callback', () => {
    const el = render(ALL, () => undefined);
    const link = el.querySelector('a');
    expect(link).not.toBeNull();
    expect(link?.getAttribute('data-cy')).toBe('dm-mapping-status-link-row-1');
    expect(link?.textContent).toContain('v1.2.3');
  });

  it('keeps the version link to itself — no other label inside it', () => {
    const el = render(ALL, () => undefined);
    const versionLink = el.querySelector('[data-cy="dm-mapping-status-link-row-1"]')!;
    expect(versionLink.textContent).not.toContain('debug');
    expect(versionLink.textContent).not.toContain('draft');
  });

  it('renders draft as its own link, flagged as action-required', () => {
    // A draft is the one state here that means "not finished": the edit does nothing until it is
    // published and the mapping activated. It links into the drawer where publishing lives.
    const el = render(ALL, () => undefined);
    const draft = el.querySelector('[data-cy="dm-mapping-status-draft-row-1"]')!;
    expect(draft.tagName).toBe('A');
    expect(draft.classList).toContain('label-warning');
    expect(draft.getAttribute('title')).toContain('publish');
  });

  it('invokes the callback when the draft badge is clicked', () => {
    const callback = jasmine.createSpy('callback');
    const el = render({ version: '1.2.3', draftDirty: true }, callback);
    el.querySelector<HTMLElement>('[data-cy="dm-mapping-status-draft-row-1"]')!
      .dispatchEvent(new MouseEvent('click'));
    expect(callback).toHaveBeenCalledWith({ id: 'row-1' });
  });

  it('tells the user where to find debug output, on hover', () => {
    const el = render(ALL, () => undefined);
    const tip = el.querySelector('[data-cy="dm-mapping-status-debug-row-1"]')!.getAttribute('title')!;
    expect(tip).toContain('microservice log');
    expect(tip).toContain('dynamic-mapper-service');
  });

  it('says what the version link actually does, on hover', () => {
    const el = render(ALL, () => undefined);
    const tip = el.querySelector('[data-cy="dm-mapping-status-link-row-1"]')!.getAttribute('title')!;
    expect(tip).toContain('version history');
  });

  it('never makes debug a link — it is status, not an action', () => {
    const el = render(ALL, () => undefined);
    expect(el.querySelector('[data-cy="dm-mapping-status-debug-row-1"]')!.tagName).toBe('SPAN');
  });

  it('renders draft as a plain badge when there is no callback', () => {
    const el = render(ALL);
    expect(el.querySelector('[data-cy="dm-mapping-status-draft-row-1"]')!.tagName).toBe('SPAN');
  });

  it('shows the history icon and link-styled version at rest, not only on hover', () => {
    // c8y's .interact only sets cursor:pointer and an <a> with no href gets no default link
    // styling, so without these the version would look like plain text until hovered.
    const el = render({ version: '1.2.3' }, () => undefined);
    expect(el.querySelector('i.dm-version-icon')).not.toBeNull();
    expect(el.querySelector('.dm-version-text')?.textContent?.trim()).toBe('v1.2.3');
  });

  it('invokes the callback with the row item when the version is clicked', () => {
    const callback = jasmine.createSpy('callback');
    const el = render({ version: '1.2.3' }, callback);
    el.querySelector('a')!.dispatchEvent(new MouseEvent('click'));
    expect(callback).toHaveBeenCalledWith({ id: 'row-1' });
  });

  it('still offers a click target when the mapping has no version yet', () => {
    const el = render({ debug: true }, () => undefined);
    const link = el.querySelector('a');
    expect(link).not.toBeNull();
    expect(link?.textContent?.trim()).toBe('—');
    expect(link?.textContent).not.toContain('debug');
  });
});
