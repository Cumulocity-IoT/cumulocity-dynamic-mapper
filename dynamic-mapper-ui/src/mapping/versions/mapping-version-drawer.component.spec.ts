import { NO_ERRORS_SCHEMA } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AlertService, BottomDrawerRef } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { Subject } from 'rxjs';
import { MappingVersionDrawerComponent } from './mapping-version-drawer.component';
import { MappingService } from '../core/mapping.service';
import { Mapping, MappingVersion } from '../../shared';

/**
 * `MappingVersionDrawerComponent` is the bottom-drawer grid listing every published
 * version of a mapping line plus its current draft, with row actions (Publish/Discard on
 * the draft, Activate/Delete on inactive published versions) that call into
 * `MappingService`. This mirrors the mock-service style used in
 * mapping-unified-editor.component.spec.ts (spy objects for MappingService/AlertService),
 * plus the `overrideComponent` workaround for CoreModule/NG0201 documented in
 * mapping-type-drawer.component.spec.ts, since this component isn't tested via its real
 * template (a c8y-data-grid) here.
 */
describe('MappingVersionDrawerComponent', () => {
  let component: MappingVersionDrawerComponent;
  let fixture: ComponentFixture<MappingVersionDrawerComponent>;
  let mockMappingService: jasmine.SpyObj<MappingService>;
  let mockAlertService: jasmine.SpyObj<AlertService>;
  let mockBsModalService: jasmine.SpyObj<BsModalService>;
  let mockDrawerRef: jasmine.SpyObj<BottomDrawerRef<any>>;

  const buildMapping = (overrides: Partial<Mapping> = {}): Mapping => ({
    id: '42',
    identifier: 'ident-42',
    name: 'My Mapping',
    version: '1.0.0',
    ...overrides
  } as Mapping);

  const buildVersion = (overrides: Partial<MappingVersion> = {}): MappingVersion => ({
    id: 'v1',
    identifier: 'ident-42',
    version: '1.0.0',
    snapshot: {} as Mapping,
    isDraft: false,
    createdAt: Date.now(),
    createdBy: 'jdoe',
    note: '',
    ...overrides
  });

  beforeEach(async () => {
    mockMappingService = jasmine.createSpyObj<MappingService>('MappingService', [
      'clearVersionsCache',
      'getVersions',
      'getDraft',
      'getMapping',
      'updateVersionNote',
      'activateVersion',
      'deleteVersion',
      'deleteDraft',
      'suggestNextVersions',
      'publishDraft'
    ]);
    mockMappingService.getVersions.and.resolveTo([buildVersion()]);
    mockMappingService.getDraft.and.resolveTo(null);
    mockMappingService.getMapping.and.resolveTo(buildMapping());

    mockAlertService = jasmine.createSpyObj<AlertService>('AlertService', ['success', 'danger'], { state: [] });
    mockBsModalService = jasmine.createSpyObj<BsModalService>('BsModalService', ['show']);
    mockDrawerRef = jasmine.createSpyObj<BottomDrawerRef<any>>('BottomDrawerRef', ['close']);

    TestBed.overrideComponent(MappingVersionDrawerComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });

    await TestBed.configureTestingModule({
      imports: [MappingVersionDrawerComponent],
      providers: [
        { provide: MappingService, useValue: mockMappingService },
        { provide: AlertService, useValue: mockAlertService },
        { provide: BsModalService, useValue: mockBsModalService },
        { provide: BottomDrawerRef, useValue: mockDrawerRef }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(MappingVersionDrawerComponent);
    component = fixture.componentInstance;
    component.mapping = buildMapping();
    component.canManage = true;
  });

  describe('ngOnInit', () => {
    it('loads versions + draft and builds grid rows without error', async () => {
      await component.ngOnInit();

      expect(mockMappingService.clearVersionsCache).toHaveBeenCalledWith('42');
      const rows = component.rows$.getValue();
      expect(rows.length).toBe(1);
      expect(rows[0].state).toBe('active');
      expect(rows[0].version).toBe('1.0.0');
      expect(component.loading).toBeFalse();
    });

    it('includes a draft row when a draft exists', async () => {
      mockMappingService.getDraft.and.resolveTo({ ...buildMapping(), versionNote: 'wip', lastUpdate: Date.now() });

      await component.ngOnInit();

      const rows = component.rows$.getValue();
      expect(rows.some(r => r.isDraft && r.state === 'draft')).toBeTrue();
    });

    it('shows a danger alert and stops loading when the reload fails', async () => {
      mockMappingService.getVersions.and.rejectWith(new Error('network down'));

      await component.ngOnInit();

      expect(mockAlertService.danger).toHaveBeenCalled();
      expect(component.loading).toBeFalse();
    });
  });

  describe('activate', () => {
    it('calls MappingService.activateVersion with the mapping id and target version', async () => {
      mockMappingService.activateVersion.and.resolveTo({} as any);

      await component.activate({ version: '2.0.0' } as any);

      expect(mockMappingService.activateVersion).toHaveBeenCalledWith('42', '2.0.0');
      expect(mockAlertService.success).toHaveBeenCalled();
      expect(component.busy).toBeFalse();
    });

    it('shows a danger alert and clears busy when activation fails', async () => {
      mockMappingService.activateVersion.and.rejectWith(new Error('locked'));

      await component.activate({ version: '2.0.0' } as any);

      expect(mockAlertService.danger).toHaveBeenCalled();
      expect(component.busy).toBeFalse();
    });
  });

  describe('remove', () => {
    function stubConfirmation(result: boolean) {
      mockBsModalService.show.and.callFake(() => {
        const closeSubject = new Subject<boolean>();
        queueMicrotask(() => closeSubject.next(result));
        return { content: { closeSubject }, hide: jasmine.createSpy('hide') } as any;
      });
    }

    it('deletes the version via MappingService when the user confirms', async () => {
      stubConfirmation(true);
      mockMappingService.deleteVersion.and.resolveTo();

      await component.remove({ version: '1.0.0', versionDisplay: 'v1.0.0' } as any);

      expect(mockMappingService.deleteVersion).toHaveBeenCalledWith('42', '1.0.0');
      expect(mockAlertService.success).toHaveBeenCalled();
    });

    it('does not call MappingService when the user cancels the confirmation', async () => {
      stubConfirmation(false);

      await component.remove({ version: '1.0.0', versionDisplay: 'v1.0.0' } as any);

      expect(mockMappingService.deleteVersion).not.toHaveBeenCalled();
    });

    it('shows a danger alert when deletion fails', async () => {
      stubConfirmation(true);
      mockMappingService.deleteVersion.and.rejectWith(new Error('406 active'));

      await component.remove({ version: '1.0.0', versionDisplay: 'v1.0.0' } as any);

      expect(mockAlertService.danger).toHaveBeenCalled();
    });
  });

  describe('publish', () => {
    function stubPublishModal(result: { version: string; note: string } | null) {
      mockBsModalService.show.and.callFake(() => {
        const closeSubject = new Subject<{ version: string; note: string } | null>();
        queueMicrotask(() => closeSubject.next(result));
        return { content: { closeSubject }, hide: jasmine.createSpy('hide') } as any;
      });
    }

    it('publishes the draft with the version/note chosen in the modal', async () => {
      mockMappingService.suggestNextVersions.and.resolveTo({ patch: '1.0.1', minor: '1.1.0', major: '2.0.0' });
      stubPublishModal({ version: '1.0.1', note: 'small fix' });
      mockMappingService.publishDraft.and.resolveTo(buildVersion({ version: '1.0.1' }));

      await component.publish();

      expect(mockMappingService.publishDraft).toHaveBeenCalledWith('42', '1.0.1', 'small fix');
      expect(mockAlertService.success).toHaveBeenCalled();
    });

    it('does nothing when the publish modal is cancelled', async () => {
      mockMappingService.suggestNextVersions.and.resolveTo({ patch: '1.0.1', minor: '1.1.0', major: '2.0.0' });
      stubPublishModal(null);

      await component.publish();

      expect(mockMappingService.publishDraft).not.toHaveBeenCalled();
    });

    it('shows a danger alert when publishing fails', async () => {
      mockMappingService.suggestNextVersions.and.resolveTo({ patch: '1.0.1', minor: '1.1.0', major: '2.0.0' });
      stubPublishModal({ version: '1.0.1', note: '' });
      mockMappingService.publishDraft.and.rejectWith(new Error('409 exists'));

      await component.publish();

      expect(mockAlertService.danger).toHaveBeenCalled();
      expect(component.busy).toBeFalse();
    });

    it('falls back to 1.0.0 suggestions when fetching suggestions fails', async () => {
      mockMappingService.suggestNextVersions.and.rejectWith(new Error('boom'));
      stubPublishModal(null);

      await component.publish();

      const initialState = (mockBsModalService.show.calls.mostRecent().args[1] as any).initialState;
      expect(initialState.suggestions).toEqual({ patch: '1.0.0', minor: '1.0.0', major: '1.0.0' });
    });
  });

  describe('close', () => {
    it('emits whether anything changed and closes the drawer', () => {
      const emitted: boolean[] = [];
      component.closeSubject.subscribe(v => emitted.push(v));

      component.close();

      expect(emitted).toEqual([false]);
      expect(mockDrawerRef.close).toHaveBeenCalled();
    });
  });
});
