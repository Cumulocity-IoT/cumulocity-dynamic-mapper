import { NO_ERRORS_SCHEMA } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { AlertService, BottomDrawerService } from '@c8y/ngx-components';
import { of } from 'rxjs';
import { MappingVersionsCountComponent } from './mapping-versions-count.component';
import { MappingService } from '../core/mapping.service';
import { Direction, Feature, Mapping, MappingEnriched, MappingVersionCount } from '../../shared';

/**
 * `MappingVersionsCountComponent` is the grid on the monitoring "Versions" page: one row
 * per mapping line showing its active version, published-version count and
 * draft-dirty status, with a "Versions" row action that opens
 * `MappingVersionDrawerComponent` in a bottom drawer. Follows the mock-service style used
 * in mapping-unified-editor.component.spec.ts, plus the `overrideComponent` workaround
 * for CoreModule/NG0201 (mapping-type-drawer.component.spec.ts) since we exercise the
 * component class rather than its real c8y-data-grid template.
 */
describe('MappingVersionsCountComponent', () => {
  let component: MappingVersionsCountComponent;
  let fixture: ComponentFixture<MappingVersionsCountComponent>;
  let mockMappingService: jasmine.SpyObj<MappingService>;
  let mockAlertService: jasmine.SpyObj<AlertService>;
  let mockRouter: jasmine.SpyObj<Router>;
  let mockBottomDrawerService: jasmine.SpyObj<BottomDrawerService>;
  let activatedRoute: { snapshot: { data: Record<string, any> } };

  const mockFeature: Feature = {
    outputMappingEnabled: true,
    externalExtensionsEnabled: true,
    userHasMappingAdminRole: true,
    userHasMappingCreateRole: true,
    pulsarAvailable: false,
    deviceIsolationMQTTServiceEnabled: false,
    suppressDeprecationWarning: false,
    acceptedDeprecationNotice: null
  } as Feature;

  const buildMapping = (overrides: Partial<Mapping> = {}): Mapping => ({
    id: '1',
    identifier: 'ident-1',
    name: 'Mapping One',
    mappingTopic: 'my/topic',
    publishTopic: 'my/pub',
    version: '1.0.0',
    draftDirty: false,
    ...overrides
  } as Mapping);

  const buildEnriched = (mapping: Mapping): MappingEnriched => ({ id: mapping.id, mapping });

  beforeEach(async () => {
    mockMappingService = jasmine.createSpyObj<MappingService>('MappingService', [
      'clearVersionsCache',
      'getMappingsObservable',
      'getVersionCounts'
    ]);
    mockMappingService.getMappingsObservable.and.returnValue(of([buildEnriched(buildMapping())]));
    mockMappingService.getVersionCounts.and.resolveTo([{ id: '1', versionCount: 3 } as MappingVersionCount]);

    mockAlertService = jasmine.createSpyObj<AlertService>('AlertService', ['danger'], { state: [] });
    mockRouter = jasmine.createSpyObj<Router>('Router', [], { url: '/monitoring/versions/inbound' });
    mockBottomDrawerService = jasmine.createSpyObj<BottomDrawerService>('BottomDrawerService', ['openDrawer']);

    activatedRoute = { snapshot: { data: { feature: mockFeature } } };

    TestBed.overrideComponent(MappingVersionsCountComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });

    await TestBed.configureTestingModule({
      imports: [MappingVersionsCountComponent],
      providers: [
        { provide: MappingService, useValue: mockMappingService },
        { provide: AlertService, useValue: mockAlertService },
        { provide: Router, useValue: mockRouter },
        { provide: ActivatedRoute, useValue: activatedRoute },
        { provide: BottomDrawerService, useValue: mockBottomDrawerService }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(MappingVersionsCountComponent);
    component = fixture.componentInstance;
  });

  describe('ngOnInit', () => {
    it('initializes without error and builds the version-count rows', async () => {
      await component.ngOnInit();

      expect(component.direction).toBe(Direction.INBOUND);
      const rows = component.rows$.getValue();
      expect(rows.length).toBe(1);
      expect(rows[0]).toEqual(jasmine.objectContaining({
        id: '1',
        name: 'Mapping One',
        topic: 'my/topic',
        activeVersion: '1.0.0',
        versionCount: 3,
        draftExists: false
      }));
    });

    it('detects outbound direction from the URL and uses the publish topic', async () => {
      Object.defineProperty(mockRouter, 'url', { value: '/monitoring/versions/outbound' });

      await component.ngOnInit();

      expect(component.direction).toBe(Direction.OUTBOUND);
      expect(component.rows$.getValue()[0].topic).toBe('my/pub');
    });

    it('shows a danger alert when loading rows fails', async () => {
      mockMappingService.getVersionCounts.and.rejectWith(new Error('boom'));

      await component.ngOnInit();

      expect(mockAlertService.danger).toHaveBeenCalled();
    });
  });

  describe('canManageMappings', () => {
    it('is true when the user has the admin or create role', async () => {
      await component.ngOnInit();
      expect(component.canManageMappings).toBeTrue();
    });

    it('is false when the user has neither role', async () => {
      activatedRoute.snapshot.data['feature'] = { ...mockFeature, userHasMappingAdminRole: false, userHasMappingCreateRole: false };
      await component.ngOnInit();
      expect(component.canManageMappings).toBeFalse();
    });
  });

  describe('openVersions', () => {
    it('opens the version drawer with the row mapping and refreshes on change', async () => {
      await component.ngOnInit();
      const row = component.rows$.getValue()[0];

      const drawerInstance = { closeSubject: of(true) };
      mockBottomDrawerService.openDrawer.and.returnValue({ instance: drawerInstance } as any);

      component.openVersions(row);

      expect(mockBottomDrawerService.openDrawer).toHaveBeenCalledWith(
        jasmine.any(Function),
        jasmine.objectContaining({ initialState: jasmine.objectContaining({ mapping: row.mapping }) })
      );
      // the drawer's closeSubject emitting `true` triggers a refresh via clearVersionsCache
      expect(mockMappingService.clearVersionsCache).toHaveBeenCalled();
    });

    it('does not refresh when the drawer reports nothing changed', async () => {
      await component.ngOnInit();
      const row = component.rows$.getValue()[0];
      mockMappingService.clearVersionsCache.calls.reset();

      const drawerInstance = { closeSubject: of(false) };
      mockBottomDrawerService.openDrawer.and.returnValue({ instance: drawerInstance } as any);

      component.openVersions(row);

      expect(mockMappingService.clearVersionsCache).not.toHaveBeenCalled();
    });
  });

  describe('refresh', () => {
    it('clears the versions cache and reloads rows', async () => {
      await component.refresh();
      expect(mockMappingService.clearVersionsCache).toHaveBeenCalled();
      expect(mockMappingService.getVersionCounts).toHaveBeenCalled();
    });
  });

  describe('ngOnDestroy', () => {
    it('completes the destroy subject without error', async () => {
      await component.ngOnInit();
      expect(() => component.ngOnDestroy()).not.toThrow();
    });
  });
});
