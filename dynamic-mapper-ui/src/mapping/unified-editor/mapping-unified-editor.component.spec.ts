/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */

import { NO_ERRORS_SCHEMA } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { Location } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { AlertService, BottomDrawerService } from '@c8y/ngx-components';
import { GlobalContextService } from '@c8y/ngx-components/global-context';
import { BsModalService } from 'ngx-bootstrap/modal';
import { of, Subject } from 'rxjs';
import { MappingUnifiedEditorComponent } from './mapping-unified-editor.component';
import { MappingStepperService } from '../service/mapping-stepper.service';
import { SubstitutionManagementService } from '../service/substitution-management.service';
import { MappingService } from '../core/mapping.service';
import { SharedService } from '../../shared';
import {
  Direction,
  Extension,
  Mapping,
  StepperConfiguration,
  TransformationType,
  MappingType,
  DeploymentMapEntry,
  Feature,
  Qos,
  RepairStrategy
} from '../../shared';
import { EditorMode } from '../shared/stepper.model';

// Tab indices (mirrors the private constants in the component under test)
const TAB_GENERAL_SETTINGS = 1;
const TAB_SELECT_TEMPLATES = 2;
const TAB_DEFINE_TRANSFORMATION = 3;
const TAB_TEST_MAPPING = 4;

/**
 * Unit tests for {@link MappingUnifiedEditorComponent} — the tabbed editor variant.
 *
 * Like the stepper spec, we `overrideComponent` to drop `CoreModule`/child-component imports,
 * the component-level providers, and the template, so we can exercise the component class in
 * isolation. The unified editor differs from the stepper in three important ways covered here:
 * tab-visibility rules, route-driven initial tab selection, and persistence via MappingService.
 */
describe('MappingUnifiedEditorComponent', () => {
  let component: MappingUnifiedEditorComponent;
  let fixture: ComponentFixture<MappingUnifiedEditorComponent>;
  let mockStepperService: jasmine.SpyObj<MappingStepperService>;
  let mockSubstitutionService: jasmine.SpyObj<SubstitutionManagementService>;
  let mockSharedService: jasmine.SpyObj<SharedService>;
  let mockAlertService: jasmine.SpyObj<AlertService>;
  let mockBottomDrawerService: jasmine.SpyObj<BottomDrawerService>;
  let mockBsModalService: jasmine.SpyObj<BsModalService>;
  let mockMappingService: jasmine.SpyObj<MappingService>;
  let mockRouter: jasmine.SpyObj<Router>;
  let mockGlobalContextService: jasmine.SpyObj<GlobalContextService>;
  let activatedRoute: { snapshot: { data: Record<string, any> } };

  let isButtonDisabled$: Subject<boolean>;
  let isSubstitutionValid$: Subject<boolean>;
  let mappingPropertyChanged$: Subject<Mapping>;

  const buildMapping = (overrides: Partial<Mapping> = {}): Mapping => ({
    id: '42',
    identifier: 'test-mapping',
    name: 'Test Mapping',
    direction: Direction.INBOUND,
    targetAPI: 'MEASUREMENT',
    mappingType: MappingType.JSON,
    transformationType: TransformationType.DEFAULT,
    substitutions: [],
    sourceTemplate: '{}',
    targetTemplate: '{}',
    mappingTopic: 'test/topic',
    mappingTopicSample: 'test/topic/sample',
    active: true,
    debug: false,
    tested: false,
    filterMapping: '',
    createNonExistingDevice: false,
    updateExistingDevice: false,
    useExternalId: false,
    externalIdType: '',
    supportsMessageContext: false,
    qos: Qos.AT_MOST_ONCE,
    lastUpdate: Date.now(),
    ...overrides
  });

  const buildConfig = (overrides: Partial<StepperConfiguration> = {}): StepperConfiguration => ({
    editorMode: EditorMode.UPDATE,
    direction: Direction.INBOUND,
    showEditorSource: true,
    showEditorTarget: true,
    allowDefiningSubstitutions: true,
    allowTestSending: true,
    allowTestTransformation: true,
    allowTemplateExpansion: false,
    allowNoDefinedIdentifier: false,
    showCodeEditor: false,
    ...overrides
  });

  const mockFeature: Feature = {
    outputMappingEnabled: true,
    externalExtensionsEnabled: true,
    userHasMappingAdminRole: true,
    userHasMappingCreateRole: true,
    pulsarAvailable: false,
    deviceIsolationMQTTServiceEnabled: false,
    suppressDeprecationWarning: false,
    acceptedDeprecationNotice: null
  };

  const deploymentMapEntry: DeploymentMapEntry = { identifier: '42', connectors: ['c1'] };

  beforeEach(async () => {
    isButtonDisabled$ = new Subject<boolean>();
    isSubstitutionValid$ = new Subject<boolean>();
    mappingPropertyChanged$ = new Subject<Mapping>();

    mockStepperService = jasmine.createSpyObj(
      'MappingStepperService',
      [
        'loadExtensions',
        'selectExtensionName',
        'updateSubstitutionValidity',
        'expandExistingTemplates',
        'evaluateFilterExpression',
        'checkAIAgentDeployment',
        'loadCodeTemplates',
        'createCodeTemplate',
        'cleanup'
      ],
      {
        countDeviceIdentifiers$: of(0),
        isSubstitutionValid$: isSubstitutionValid$,
        isContentChangeValid$: new Subject(),
        extensionEvents$: of([]),
        isButtonDisabled$: isButtonDisabled$,
        sourceCustomMessage$: of(''),
        targetCustomMessage$: of(''),
        mappingPropertyChanged$: mappingPropertyChanged$
      }
    );

    mockSubstitutionService = jasmine.createSpyObj('SubstitutionManagementService', [
      'isSubstitutionValid',
      'addSubstitution'
    ]);

    mockSharedService = jasmine.createSpyObj('SharedService', [
      'getFeatures',
      'getServiceConfiguration',
      'getCodeTemplates'
    ]);

    mockAlertService = jasmine.createSpyObj('AlertService', ['add', 'remove', 'clearAll', 'success', 'danger'], {
      state: []
    });
    mockBottomDrawerService = jasmine.createSpyObj('BottomDrawerService', ['openDrawer']);
    mockBsModalService = jasmine.createSpyObj('BsModalService', ['show']);
    mockMappingService = jasmine.createSpyObj('MappingService', [
      'updateMapping',
      'updateDefinedDeploymentMapEntry'
    ]);
    mockRouter = jasmine.createSpyObj('Router', ['navigateByUrl'], { url: '/mappings/inbound/edit/42' });
    mockGlobalContextService = jasmine.createSpyObj('GlobalContextService', ['register', 'unregister']);
    activatedRoute = {
      snapshot: {
        data: {
          mappingEdit: {
            mapping: buildMapping(),
            stepperConfiguration: buildConfig(),
            deploymentMapEntry
          }
        }
      }
    };

    mockStepperService.loadExtensions.and.returnValue(Promise.resolve(new Map<string, Extension>()));
    mockStepperService.expandExistingTemplates.and.returnValue({ sourceTemplate: {}, targetTemplate: {} });
    mockStepperService.checkAIAgentDeployment.and.returnValue(
      Promise.resolve({ aiAgent: null, aiAgentDeployed: false })
    );
    mockStepperService.loadCodeTemplates.and.returnValue(Promise.resolve(new Map()));
    mockSharedService.getFeatures.and.returnValue(Promise.resolve(mockFeature));
    mockSharedService.getServiceConfiguration.and.returnValue(Promise.resolve({} as any));
    mockSharedService.getCodeTemplates.and.returnValue(Promise.resolve({} as any));
    mockMappingService.updateMapping.and.returnValue(Promise.resolve(buildMapping()));
    mockMappingService.updateDefinedDeploymentMapEntry.and.returnValue(Promise.resolve({} as any));

    TestBed.overrideComponent(MappingUnifiedEditorComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });

    await TestBed.configureTestingModule({
      imports: [MappingUnifiedEditorComponent],
      providers: [
        { provide: MappingStepperService, useValue: mockStepperService },
        { provide: SubstitutionManagementService, useValue: mockSubstitutionService },
        { provide: SharedService, useValue: mockSharedService },
        { provide: AlertService, useValue: mockAlertService },
        { provide: BottomDrawerService, useValue: mockBottomDrawerService },
        { provide: BsModalService, useValue: mockBsModalService },
        { provide: MappingService, useValue: mockMappingService },
        { provide: Router, useValue: mockRouter },
        { provide: ActivatedRoute, useValue: activatedRoute },
        { provide: Location, useValue: jasmine.createSpyObj('Location', ['back', 'path']) },
        { provide: GlobalContextService, useValue: mockGlobalContextService }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(MappingUnifiedEditorComponent);
    component = fixture.componentInstance;
  });

  describe('ngOnInit', () => {
    it('registers the global context with auto-refresh hidden', async () => {
      await component.ngOnInit();
      expect(mockGlobalContextService.register).toHaveBeenCalledWith(
        'mapping-unified-editor',
        jasmine.objectContaining({ showAutoRefresh: false })
      );
    });

    it('reads mapping/config/deployment from the resolved route data', async () => {
      await component.ngOnInit();
      expect(component.mapping.id).toBe('42');
      expect(component.deploymentMapEntry).toBe(deploymentMapEntry);
      expect(component.stepperViewModel).toBeDefined();
    });

    it('opens on the Transformation tab for a regular mapping', async () => {
      await component.ngOnInit();
      expect(component.activeTabIndex).toBe(TAB_DEFINE_TRANSFORMATION);
    });

    it('opens on the General-settings tab for an EXTENSION_JAVA mapping', async () => {
      activatedRoute.snapshot.data['mappingEdit'].mapping = buildMapping({
        transformationType: TransformationType.EXTENSION_JAVA
      });
      await component.ngOnInit();
      expect(component.activeTabIndex).toBe(TAB_GENERAL_SETTINGS);
    });

    it('opens on the General-settings tab for a PROTOBUF_INTERNAL mapping', async () => {
      activatedRoute.snapshot.data['mappingEdit'].mapping = buildMapping({
        mappingType: MappingType.PROTOBUF_INTERNAL
      });
      await component.ngOnInit();
      expect(component.activeTabIndex).toBe(TAB_GENERAL_SETTINGS);
    });
  });

  describe('isTabVisible', () => {
    it('shows all tabs when no step-skipping is configured', () => {
      component.mapping = buildMapping();
      component.stepperConfiguration = buildConfig({ advanceFromStepToEndStep: undefined });
      [0, 1, 2, 3, 4].forEach((i) => expect(component.isTabVisible(i)).toBe(true));
    });

    it('hides intermediate tabs but keeps the Testing tab when advancing to the end step', () => {
      component.mapping = buildMapping();
      component.stepperConfiguration = buildConfig({ advanceFromStepToEndStep: 1 });

      expect(component.isTabVisible(0)).toBe(true); // connector
      expect(component.isTabVisible(1)).toBe(true); // general (== skip)
      expect(component.isTabVisible(2)).toBe(false); // templates (skipped)
      expect(component.isTabVisible(3)).toBe(false); // transformation (skipped)
      expect(component.isTabVisible(TAB_TEST_MAPPING)).toBe(true); // testing always visible
    });

    it('hides the Testing tab for deprecated SUBSTITUTION_AS_CODE mappings', () => {
      // eslint-disable-next-line deprecation/deprecation
      component.mapping = buildMapping({ transformationType: TransformationType.SUBSTITUTION_AS_CODE });
      component.stepperConfiguration = buildConfig();
      expect(component.isTabVisible(TAB_TEST_MAPPING)).toBe(false);
    });
  });

  describe('onSelectSubstitution', () => {
    beforeEach(() => {
      component.mapping = buildMapping({
        substitutions: [
          { pathSource: '$.a', pathTarget: '$.x', repairStrategy: RepairStrategy.DEFAULT, expandArray: false },
          { pathSource: '$.b', pathTarget: '$.y', repairStrategy: RepairStrategy.DEFAULT, expandArray: false }
        ]
      });
      component.stepperConfiguration = buildConfig();
    });

    it('selects a substitution by index and populates the model', async () => {
      await component.onSelectSubstitution(1);
      expect(component.selectedSubstitution).toBe(1);
      expect(component.substitutionModel.pathSource).toBe('$.b');
      expect(component.substitutionModel.pathTarget).toBe('$.y');
    });

    it('ignores a negative index', async () => {
      component.selectedSubstitution = 0;
      await component.onSelectSubstitution(-1);
      expect(component.selectedSubstitution).toBe(0);
    });

    it('ignores an out-of-range index', async () => {
      component.selectedSubstitution = 0;
      await component.onSelectSubstitution(99);
      expect(component.selectedSubstitution).toBe(0);
    });
  });

  describe('YAML <-> configuration helpers', () => {
    it('serialises and parses round-trip', () => {
      const yaml = component.configurationToYaml({ a: 1 });
      expect(component.yamlToConfiguration(yaml)).toEqual({ a: 1 });
    });

    it('returns empty/undefined for empty inputs', () => {
      expect(component.configurationToYaml(undefined)).toBe('');
      expect(component.yamlToConfiguration('')).toBeUndefined();
    });
  });

  describe('Code template selection', () => {
    it('appends an ESM export for Smart Functions when Support ESM is enabled', () => {
      component.mapping = buildMapping({ transformationType: TransformationType.SMART_FUNCTION });
      component.codeTemplatesDecoded.set('t1', { code: 'function onMessage() {}' } as any);
      component.templateId = 't1' as any;
      component.serviceConfiguration = { supportESM: true } as any;

      component.onSelectCodeTemplate();

      expect(component.mappingCode).toContain('export { onMessage };');
    });

    it('updates mappingCode on value change', () => {
      component.onValueCodeChange('x=1');
      expect(component.mappingCode).toBe('x=1');
    });
  });

  describe('onTabSelected', () => {
    beforeEach(() => {
      component.mapping = buildMapping();
      component.stepperConfiguration = buildConfig();
      component.sourceTemplate = {};
      component.targetTemplate = {};
    });

    it('revalidates substitutions against the newly active tab index', async () => {
      await component.onTabSelected(TAB_DEFINE_TRANSFORMATION);
      expect(component.activeTabIndex).toBe(TAB_DEFINE_TRANSFORMATION);
      expect(component.currentStepIndex).toBe(TAB_DEFINE_TRANSFORMATION);
      expect(mockStepperService.updateSubstitutionValidity).toHaveBeenCalled();
    });

    it('emits a testing template when the Testing tab is opened', async () => {
      const emitted: Mapping[] = [];
      component.updateTestingTemplate.subscribe((m) => emitted.push(m));
      await component.onTabSelected(TAB_TEST_MAPPING);
      expect(emitted.length).toBeGreaterThan(0);
    });

    it('loads extensions when the General-settings tab is opened', async () => {
      await component.onTabSelected(TAB_GENERAL_SETTINGS);
      expect(mockStepperService.loadExtensions).toHaveBeenCalledWith(component.mapping);
    });
  });

  describe('onCommitButton', () => {
    beforeEach(() => {
      component.mapping = buildMapping();
      component.stepperConfiguration = buildConfig({ allowTemplateExpansion: false });
      component.deploymentMapEntry = deploymentMapEntry;
      component.sourceTemplate = { a: 1 };
      component.targetTemplate = { b: 2 };
      component.stepperViewModel = { showExtensionSelectors: false } as any;
      component.templateForm = new FormGroup({
        extensionName: new FormControl(''),
        eventName: new FormControl('')
      });
    });

    it('persists the mapping and deployment, then navigates back to the grid', async () => {
      await component.onCommitButton();

      expect(mockMappingService.updateMapping).toHaveBeenCalled();
      expect(mockMappingService.updateDefinedDeploymentMapEntry).toHaveBeenCalledWith(deploymentMapEntry);
      expect(mockAlertService.success).toHaveBeenCalled();
      expect(mockRouter.navigateByUrl).toHaveBeenCalledWith('/mappings/inbound');
    });

    it('serialises templates as JSON strings before saving (no expansion)', async () => {
      await component.onCommitButton();
      expect(component.mapping.sourceTemplate).toBe(JSON.stringify({ a: 1 }));
      expect(component.mapping.targetTemplate).toBe(JSON.stringify({ b: 2 }));
    });

    it('shows a danger alert and does not navigate when the update fails', async () => {
      mockMappingService.updateMapping.and.returnValue(Promise.reject(new Error('boom')));

      await component.onCommitButton();

      expect(mockAlertService.danger).toHaveBeenCalled();
      expect(mockRouter.navigateByUrl).not.toHaveBeenCalled();
    });

    it('blocks the commit and jumps to the Templates tab when a required extension is missing', async () => {
      component.stepperViewModel = { showExtensionSelectors: true } as any;
      component.templateForm = new FormGroup({
        extensionName: new FormControl('', Validators.required),
        eventName: new FormControl('', Validators.required)
      });

      await component.onCommitButton();

      expect(component.activeTabIndex).toBe(TAB_SELECT_TEMPLATES);
      expect(mockMappingService.updateMapping).not.toHaveBeenCalled();
    });
  });

  describe('Navigation', () => {
    it('navigates back to the grid (stripping the /edit/:id segment) on cancel', () => {
      component.onCancel();
      expect(mockRouter.navigateByUrl).toHaveBeenCalledWith('/mappings/inbound');
    });
  });

  describe('Deployment map entry changes', () => {
    it('disables the save button when no connectors are assigned', (done) => {
      component.deploymentMapEntry = { identifier: '42', connectors: [] };
      isButtonDisabled$.subscribe((disabled) => {
        expect(disabled).toBe(true);
        done();
      });
      component.deploymentMapEntryChange({ identifier: '42', connectors: [] });
    });
  });

  describe('Lifecycle', () => {
    it('unregisters the global context and cleans up on destroy', async () => {
      await component.ngOnInit();
      component.ngOnDestroy();
      expect(mockGlobalContextService.unregister).toHaveBeenCalledWith('mapping-unified-editor');
      expect(mockStepperService.cleanup).toHaveBeenCalled();
    });
  });
});
