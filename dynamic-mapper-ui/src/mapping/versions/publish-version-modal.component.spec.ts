import { NO_ERRORS_SCHEMA } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PublishVersionModalComponent } from './publish-version-modal.component';

/**
 * `PublishVersionModalComponent` is a small bsModal dialog: it collects a semver label
 * (pre-filled with the suggested patch bump) and an optional note, then resolves
 * `closeSubject` with `{ version, note }` on confirm or `null` on cancel. It has no
 * service dependencies of its own — the surrounding drawer (see
 * mapping-version-drawer.component.ts) is responsible for calling MappingService.
 *
 * Its template imports CoreModule, which pulls in the c8y app-shell DI graph
 * (ApplicationService) unavailable in the test injector (NG0201) — same issue documented
 * in mapping-type-drawer.component.spec.ts / mapping-unified-editor.component.spec.ts.
 * We use the same overrideComponent trick to exercise the component class in isolation.
 */
describe('PublishVersionModalComponent', () => {
  let component: PublishVersionModalComponent;
  let fixture: ComponentFixture<PublishVersionModalComponent>;

  beforeEach(async () => {
    TestBed.overrideComponent(PublishVersionModalComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });

    await TestBed.configureTestingModule({
      imports: [PublishVersionModalComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(PublishVersionModalComponent);
    component = fixture.componentInstance;
    component.mappingName = 'My Mapping';
    component.currentVersion = '1.0.0';
    component.suggestions = { patch: '1.0.1', minor: '1.1.0', major: '2.0.0' };
  });

  it('creates without error and pre-fills the version with the suggested patch bump', () => {
    component.ngOnInit();
    expect(component.version).toBe('1.0.1');
  });

  describe('pick', () => {
    it('overwrites the version field with the chosen suggestion', () => {
      component.ngOnInit();
      component.pick(component.suggestions.major);
      expect(component.version).toBe('2.0.0');
    });
  });

  describe('isValid', () => {
    it('accepts a well-formed MAJOR.MINOR.PATCH label', () => {
      component.version = '3.4.5';
      expect(component.isValid()).toBeTrue();
    });

    it('rejects a malformed label', () => {
      component.version = 'v3.4';
      expect(component.isValid()).toBeFalse();
    });

    it('rejects an empty label', () => {
      component.version = '';
      expect(component.isValid()).toBeFalse();
    });
  });

  describe('confirm', () => {
    it('emits {version, note} on closeSubject and completes it when valid', (done) => {
      component.version = '1.2.3';
      component.note = '  Fixed a bug  ';

      const emitted: Array<{ version: string; note: string } | null> = [];
      component.closeSubject.subscribe({
        next: (v) => emitted.push(v),
        complete: () => {
          expect(emitted).toEqual([{ version: '1.2.3', note: 'Fixed a bug' }]);
          done();
        }
      });

      component.confirm();
    });

    it('does nothing when the version is invalid', () => {
      component.version = 'not-a-semver';
      let called = false;
      component.closeSubject.subscribe(() => (called = true));

      component.confirm();

      expect(called).toBeFalse();
    });
  });

  describe('cancel', () => {
    it('emits null on closeSubject and completes it', (done) => {
      const emitted: Array<{ version: string; note: string } | null> = [];
      component.closeSubject.subscribe({
        next: (v) => emitted.push(v),
        complete: () => {
          expect(emitted).toEqual([null]);
          done();
        }
      });

      component.cancel();
    });
  });
});
