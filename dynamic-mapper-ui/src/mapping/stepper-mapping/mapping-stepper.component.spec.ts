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
import { FormControl, FormGroup } from '@angular/forms';
import { of, Subject } from 'rxjs';
import { MappingStepperComponent } from './mapping-stepper.component';
import { MappingStepperService, EditorSessionResult } from '../service/mapping-stepper.service';
import { SubstitutionManagementService } from '../service/substitution-management.service';
import { SharedService } from '../../shared';
import { AlertService, BottomDrawerService } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import {
  Direction,
  Extension,
  Mapping,
  StepperConfiguration,
  TransformationType,
  MappingType,
  DeploymentMapEntry,
  Feature,
  Qos
} from '../../shared';
import {
  EditorMode,
  STEP_GENERAL_SETTINGS,
  STEP_SELECT_TEMPLATES,
  STEP_DEFINE_SUBSTITUTIONS,
  STEP_TEST_MAPPING
} from '../shared/stepper.model';
import { configurationToYaml, yamlToConfiguration } from '../shared/util';

/**
 * Unit tests for {@link MappingStepperComponent}.
 *
 * Setup note: the component imports `CoreModule` and several heavy child step components,
 * which eagerly bootstrap the Cumulocity app-shell DI graph (`ApplicationService`, …) that
 * a unit-test `TestBed` does not provide. We therefore `overrideComponent` to drop those
 * imports, the component-level service providers (so our spies are injected instead of the
 * real services), and the template (these tests exercise the component CLASS, not rendering).
 */
describe('MappingStepperComponent', () => {
  let component: MappingStepperComponent;
  let fixture: ComponentFixture<MappingStepperComponent>;
  let mockStepperService: jasmine.SpyObj<MappingStepperService>;
  let mockSubstitutionService: jasmine.SpyObj<SubstitutionManagementService>;
  let mockSharedService: jasmine.SpyObj<SharedService>;
  let mockAlertService: jasmine.SpyObj<AlertService>;
  let mockBottomDrawerService: jasmine.SpyObj<BottomDrawerService>;
  let mockBsModalService: jasmine.SpyObj<BsModalService>;

  let mappingPropertyChanged$: Subject<Mapping>;
  let isButtonDisabled$: Subject<boolean>;
  let isSubstitutionValid$: Subject<boolean>;

  const buildMapping = (overrides: Partial<Mapping> = {}): Mapping => ({
    id: '1',
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

  const mockStepperConfiguration: StepperConfiguration = {
    editorMode: EditorMode.CREATE,
    direction: Direction.INBOUND,
    showEditorSource: true,
    showEditorTarget: true,
    allowDefiningSubstitutions: true,
    allowTestSending: true,
    allowTestTransformation: true,
    allowTemplateExpansion: false,
    allowNoDefinedIdentifier: false,
    showCodeEditor: false
  };

  const mockDeploymentMapEntry: DeploymentMapEntry = {
    identifier: 'test-connector',
    connectors: ['connector-1']
  };

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

  // Default stand-in for MappingStepperService.initializeEditorSession()'s return value — the
  // real logic behind it (direction-based systems, schema lookups, read-only gating, code-editor
  // help text, ...) is unit tested directly on the service (mapping-stepper.service.spec.ts);
  // these component tests only need a realistic-shaped result to apply onto their own fields.
  const buildEditorSessionResult = (overrides: Partial<EditorSessionResult> = {}): EditorSessionResult => ({
    stepperViewModel: {} as any,
    extensionEventItems$: of([]),
    targetSystem: 'Cumulocity',
    sourceSystem: 'Broker',
    editorOptions: {} as any,
    templateForm: new FormGroup({
      extensionName: new FormControl(''),
      eventName: new FormControl(''),
      extensionParameter: new FormControl(''),
      sampleTargetTemplatesButton: new FormControl(false)
    }),
    editorTemplatesReadOnly: false,
    feature: mockFeature,
    serviceConfiguration: {} as any,
    aiAgent: null,
    aiAgentDeployed: false,
    filterFormlyFields: [],
    codeTemplates: {} as any,
    codeTemplatesDecoded: new Map(),
    codeTemplateEntries: [],
    codeTemplateItems: [],
    codeEditorHelp: 'help text',
    codeEditorLabel: 'JavaScript callback for Smart functions',
    schemaSource: { source: true },
    schemaTarget: { target: true },
    ...overrides
  });

  beforeEach(async () => {
    mappingPropertyChanged$ = new Subject<Mapping>();
    isButtonDisabled$ = new Subject<boolean>();
    isSubstitutionValid$ = new Subject<boolean>();

    mockStepperService = jasmine.createSpyObj(
      'MappingStepperService',
      [
        'loadExtensions',
        'selectExtensionName',
        'updateSubstitutionValidity',
        'refreshSubstitutionValidity',
        'expandTemplates',
        'expandExistingTemplates',
        'evaluateFilterExpression',
        'checkAIAgentDeployment',
        'loadCodeTemplates',
        'createCodeTemplate',
        'cleanup',
        'raiseAlert',
        'patchExtensionFormValues',
        'applyExtensionNameSelection',
        'applyExtensionEventSelection',
        'applyTargetAPIChange',
        'computeSampleTargetTemplate',
        'computeCodeFromTemplate',
        'computeCodeTemplateEntries',
        'computeExtensionItems',
        'createCodeTemplateAndRefresh',
        'initializeEditorSession',
        'registerCompletionProvider',
        'encodeMappingForCommit'
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

    mockStepperService.loadExtensions.and.returnValue(Promise.resolve(new Map<string, Extension>()));
    mockStepperService.expandTemplates.and.returnValue({ sourceTemplate: {}, targetTemplate: {} });
    mockStepperService.expandExistingTemplates.and.returnValue({ sourceTemplate: {}, targetTemplate: {} });
    mockStepperService.evaluateFilterExpression.and.returnValue(
      Promise.resolve({ result: '', resultType: 'empty', valid: true })
    );
    mockStepperService.checkAIAgentDeployment.and.returnValue(
      Promise.resolve({ aiAgent: null, aiAgentDeployed: false })
    );
    mockStepperService.loadCodeTemplates.and.returnValue(Promise.resolve(new Map()));
    mockStepperService.initializeEditorSession.and.returnValue(Promise.resolve(buildEditorSessionResult()));
    mockStepperService.registerCompletionProvider.and.returnValue(Promise.resolve());
    mockSharedService.getFeatures.and.returnValue(Promise.resolve(mockFeature));
    mockSharedService.getServiceConfiguration.and.returnValue(Promise.resolve({} as any));
    mockSharedService.getCodeTemplates.and.returnValue(Promise.resolve({} as any));

    TestBed.overrideComponent(MappingStepperComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });

    await TestBed.configureTestingModule({
      imports: [MappingStepperComponent],
      providers: [
        { provide: MappingStepperService, useValue: mockStepperService },
        { provide: SubstitutionManagementService, useValue: mockSubstitutionService },
        { provide: SharedService, useValue: mockSharedService },
        { provide: AlertService, useValue: mockAlertService },
        { provide: BottomDrawerService, useValue: mockBottomDrawerService },
        { provide: BsModalService, useValue: mockBsModalService }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(MappingStepperComponent);
    component = fixture.componentInstance;

    component.mapping = buildMapping();
    component.stepperConfiguration = { ...mockStepperConfiguration };
    component.deploymentMapEntry = mockDeploymentMapEntry;
  });

  describe('Component Initialization', () => {
    it('creates the component', () => {
      expect(component).toBeTruthy();
    });

    it('injects the mocked stepper service (component-level providers are overridden)', () => {
      expect(component['stepperService']).toBe(mockStepperService);
    });
  });

  // The real bootstrap logic (view model, systems, schemas, read-only gating, code-editor help
  // text, ...) moved into MappingStepperService.initializeEditorSession() (Phase 4) and is unit
  // tested directly there (mapping-stepper.service.spec.ts). These tests only verify the
  // component delegates to it with the right arguments and applies the result back onto its own
  // fields — plus the one genuinely stepper-specific argument (disableExtensionSelectorsWhenHidden = true).
  describe('ngOnInit — editor session bootstrap', () => {
    it('delegates to initializeEditorSession with its own mapping/config/destroy$/callbacks, and the stepper-only selector-disable flag', async () => {
      await component.ngOnInit();

      expect(mockStepperService.initializeEditorSession).toHaveBeenCalledWith(
        component.mapping,
        component.stepperConfiguration,
        jasmine.any(Subject),
        jasmine.objectContaining({
          onSelectExtensionName: jasmine.any(Function),
          onSelectExtensionEvent: jasmine.any(Function),
          getSourceTemplate: jasmine.any(Function),
          setSourceTemplate: jasmine.any(Function)
        }),
        true
      );
    });

    it('applies the returned session fields onto its own state', async () => {
      const session = buildEditorSessionResult({
        targetSystem: 'X-target',
        sourceSystem: 'Y-source',
        schemaSource: { a: 1 },
        schemaTarget: { b: 2 },
        codeEditorHelp: 'H',
        codeEditorLabel: 'L'
      });
      mockStepperService.initializeEditorSession.and.returnValue(Promise.resolve(session));

      await component.ngOnInit();

      expect(component.stepperViewModel).toBe(session.stepperViewModel);
      expect(component.templateForm).toBe(session.templateForm);
      expect(component.targetSystem).toBe('X-target');
      expect(component.sourceSystem).toBe('Y-source');
      expect(component.schemaSource).toEqual({ a: 1 });
      expect(component.schemaTarget).toEqual({ b: 2 });
      expect(component.codeEditorHelp).toBe('H');
      expect(component.codeEditorLabel).toBe('L');
      expect(component.feature).toBe(session.feature);
      expect(component.serviceConfiguration).toBe(session.serviceConfiguration);
      expect(component.aiAgent).toBe(session.aiAgent);
      expect(component.aiAgentDeployed).toBe(session.aiAgentDeployed);
      expect(component.filterFormlyFields).toBe(session.filterFormlyFields);
      expect(component.codeTemplates).toBe(session.codeTemplates);
      expect(component.codeTemplatesDecoded).toBe(session.codeTemplatesDecoded);
      expect(component.codeTemplateEntries).toBe(session.codeTemplateEntries);
      expect(component.codeTemplateItems).toBe(session.codeTemplateItems);
    });

    it('makes template editors read-only when the session reports editorTemplatesReadOnly', async () => {
      mockStepperService.initializeEditorSession.and.returnValue(
        Promise.resolve(buildEditorSessionResult({ editorTemplatesReadOnly: true }))
      );
      await component.ngOnInit();
      expect(component.editorOptionsSourceTemplate.readOnly).toBe(true);
      expect(component.editorOptionsTargetTemplate.readOnly).toBe(true);
    });

    it('leaves template editors editable when editorTemplatesReadOnly is false', async () => {
      await component.ngOnInit();
      expect(component.editorOptionsSourceTemplate.readOnly).toBe(false);
      expect(component.editorOptionsTargetTemplate.readOnly).toBe(false);
    });
  });

  // configurationToYaml/yamlToConfiguration moved to mapping/shared/util.ts (shared with
  // MappingUnifiedEditorComponent) — see util.spec.ts for their unit tests. Kept a thin
  // smoke-test here since the stepper still relies on them being wired in correctly.
  describe('YAML <-> configuration helpers', () => {
    it('round-trips a configuration object through the shared util functions', () => {
      const yaml = configurationToYaml({ host: 'localhost', port: 1883 });
      expect(yaml).toContain('host: localhost');
      expect(yamlToConfiguration(yaml)).toEqual({ host: 'localhost', port: 1883 });
    });
  });

  // The actual business logic behind these wrapper methods (mutating `mapping.extension`,
  // computing schemas, ESM-export detection, ...) moved to MappingStepperService as part of
  // Phase 3 of docs/planning/IMPLEMENTATION-PLAN-STEPPER-UNIFIED-EDITOR-DEDUP.md, and is unit
  // tested directly there (mapping-stepper.service.spec.ts). These tests only verify the
  // component delegates to the (mocked) service with the right arguments and applies its
  // result back onto its own fields.

  describe('Extension selection', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('delegates extension name selection to the service', () => {
      component.mapping = buildMapping();
      component.extensions = new Map();

      component.onSelectExtensionName('my-extension');

      expect(mockStepperService.applyExtensionNameSelection).toHaveBeenCalledWith(
        'my-extension',
        component.mapping,
        component.extensions
      );
    });

    it('applies the returned hasExtensionParameter flag when defined', () => {
      mockStepperService.applyExtensionEventSelection.and.returnValue(true);

      component.onSelectExtensionEvent('evt');

      expect(mockStepperService.applyExtensionEventSelection).toHaveBeenCalledWith(
        'evt', component.mapping, component.extensions, component.templateForm
      );
      expect(component.hasExtensionParameter).toBe(true);
    });

    it('leaves hasExtensionParameter unchanged when the service returns undefined (no matching event)', () => {
      component.hasExtensionParameter = true;
      mockStepperService.applyExtensionEventSelection.and.returnValue(undefined);

      component.onSelectExtensionEvent('missing-evt');

      expect(component.hasExtensionParameter).toBe(true);
    });
  });

  describe('Code editing & templates', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('updates mappingCode on value change', () => {
      component.onValueCodeChange('const x = 1;');
      expect(component.mappingCode).toBe('const x = 1;');
    });

    it('leaves mappingCode untouched when the service reports no template selected', () => {
      component.mappingCode = 'untouched';
      mockStepperService.computeCodeFromTemplate.and.returnValue(undefined);

      component.onSelectCodeTemplate();

      expect(component.mappingCode).toBe('untouched');
    });

    it('applies the code computed by the service', () => {
      mockStepperService.computeCodeFromTemplate.and.returnValue('function onMessage() {}');

      component.onSelectCodeTemplate();

      expect(mockStepperService.computeCodeFromTemplate).toHaveBeenCalledWith(
        component.codeTemplatesDecoded, component.templateId, component.serviceConfiguration, component.mapping.transformationType
      );
      expect(component.mappingCode).toBe('function onMessage() {}');
    });
  });

  describe('Target API changes', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('applies the target schema returned by the service for INBOUND', async () => {
      mockStepperService.applyTargetAPIChange.and.returnValue({ schemaTarget: { fromService: true } });

      await component.onTargetAPIChanged('EVENT');

      expect(mockStepperService.applyTargetAPIChange).toHaveBeenCalledWith(
        component.mapping, component.stepperConfiguration.direction, 'EVENT'
      );
      expect(component.schemaTarget).toEqual({ fromService: true });
    });

    it('applies the source schema returned by the service for OUTBOUND', async () => {
      mockStepperService.applyTargetAPIChange.and.returnValue({ schemaSource: { fromService: true } });

      await component.onTargetAPIChanged('EVENT');

      expect(component.schemaSource).toEqual({ fromService: true });
    });
  });

  describe('Step transitions', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('updates the current index and revalidates substitutions on every step change', async () => {
      await component.onStepChange({ selectedIndex: STEP_SELECT_TEMPLATES });
      expect(component.currentStepIndex).toBe(STEP_SELECT_TEMPLATES);
      expect(mockStepperService.refreshSubstitutionValidity).toHaveBeenCalledWith(
        component.mapping,
        component.stepperConfiguration,
        true // STEP_SELECT_TEMPLATES < STEP_DEFINE_SUBSTITUTIONS
      );
    });

    it('loads extensions when entering the general-settings step', async () => {
      await component.onStepChange({ selectedIndex: STEP_GENERAL_SETTINGS });
      expect(mockStepperService.loadExtensions).toHaveBeenCalledWith(component.mapping);
    });

    it('expands templates when moving forward into the select-templates step', async () => {
      component.stepperForward = true;
      await component.onStepChange({ selectedIndex: STEP_SELECT_TEMPLATES });
      // CREATE mode + default source template => expandTemplates is used.
      expect(mockStepperService.expandTemplates).toHaveBeenCalled();
      expect(component.sourceTemplate).toBeDefined();
    });

    it('pushes a testing template when entering the test-mapping step', async () => {
      component.sourceTemplate = { a: 1 };
      component.targetTemplate = { b: 2 };
      const emitted: Mapping[] = [];
      component.updateTestingTemplate.subscribe((m) => emitted.push(m));

      await component.onStepChange({ selectedIndex: STEP_TEST_MAPPING });

      expect(emitted.length).toBeGreaterThan(0);
      expect(emitted[emitted.length - 1].sourceTemplate).toBe(JSON.stringify({ a: 1 }));
    });

    it('revalidates and emits a testing template on the define-substitutions step', async () => {
      component.sourceTemplate = { a: 1 };
      component.targetTemplate = { b: 2 };
      const emitted: Mapping[] = [];
      component.updateTestingTemplate.subscribe((m) => emitted.push(m));

      await component.onStepChange({ selectedIndex: STEP_DEFINE_SUBSTITUTIONS });

      expect(component.currentStepIndex).toBe(STEP_DEFINE_SUBSTITUTIONS);
      expect(emitted.length).toBeGreaterThan(0);
    });
  });

  // The real encoding logic (JSON-stringify/reduce, base64 code encoding, content-change
  // detection, substitutions-as-code guard) moved to MappingStepperService.encodeMappingForCommit
  // (Phase 5) and is unit tested directly there (mapping-stepper.service.spec.ts). These tests
  // only verify the component delegates to it and reacts correctly to its result.
  describe('Commit & cancel', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('emits cancel', (done) => {
      component.cancel.subscribe(() => done());
      component.onCancelButton();
    });

    it('delegates to encodeMappingForCommit and emits its result on success', (done) => {
      component.sourceTemplate = { a: 1 };
      component.targetTemplate = { b: 2 };
      component.mappingCode = 'code';
      component['initialContentSnapshot'] = undefined;
      mockStepperService.encodeMappingForCommit.and.returnValue({ mapping: component.mapping, contentChanged: true });

      component.commit.subscribe((result: { mapping: Mapping; contentChanged: boolean }) => {
        expect(mockStepperService.encodeMappingForCommit).toHaveBeenCalledWith(
          component.mapping,
          { a: 1 },
          { b: 2 },
          'code',
          undefined,
          component.stepperConfiguration.allowTemplateExpansion,
          component.stepperConfiguration.editorMode
        );
        expect(result).toEqual({ mapping: component.mapping, contentChanged: true });
        done();
      });

      component.onCommitButton();
    });

    it('raises an alert and does not emit commit when encodeMappingForCommit reports an error', () => {
      mockStepperService.encodeMappingForCommit.and.returnValue({ error: 'Internal error in editor. Try again!' });
      const commitSpy = jasmine.createSpy('commit');
      component.commit.subscribe(commitSpy);

      component.onCommitButton();

      expect(mockStepperService.raiseAlert).toHaveBeenCalledWith({ type: 'warning', text: 'Internal error in editor. Try again!' });
      expect(commitSpy).not.toHaveBeenCalled();
    });
  });

  // Alert filtering/adding itself moved to MappingStepperService.raiseAlert (Phase 3) and is
  // unit tested there; this only verifies the component delegates to it.
  describe('Alert management', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('delegates to the stepper service', () => {
      const alert = { type: 'danger', text: 'boom' } as any;
      component.raiseAlert(alert);
      expect(mockStepperService.raiseAlert).toHaveBeenCalledWith(alert);
    });
  });

  describe('Deployment map entry changes', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('disables the button when no connectors are assigned', (done) => {
      component.deploymentMapEntry = { identifier: 'x', connectors: [] };
      isButtonDisabled$.subscribe((disabled) => {
        expect(disabled).toBe(true);
        done();
      });
      component.deploymentMapEntryChange({ identifier: 'x', connectors: [] });
    });

    it('enables the button when connectors are assigned', (done) => {
      component.deploymentMapEntry = { identifier: 'x', connectors: ['c1'] };
      isButtonDisabled$.subscribe((disabled) => {
        expect(disabled).toBe(false);
        done();
      });
      component.deploymentMapEntryChange({ identifier: 'x', connectors: ['c1'] });
    });
  });

  describe('Substitution validity subscription', () => {
    it('mirrors the service validity stream into the template form errors', async () => {
      await component.ngOnInit();

      isSubstitutionValid$.next(false);
      expect(component.templateForm.errors).toEqual({ incorrect: true });

      isSubstitutionValid$.next(true);
      expect(component.templateForm.errors).toBeNull();
    });
  });

  describe('Lifecycle', () => {
    it('tears down subscriptions and the stepper service on destroy', async () => {
      await component.ngOnInit();
      const destroy$ = component['destroy$'];
      spyOn(destroy$, 'next').and.callThrough();
      spyOn(destroy$, 'complete').and.callThrough();

      component.ngOnDestroy();

      expect(destroy$.next).toHaveBeenCalled();
      expect(destroy$.complete).toHaveBeenCalled();
      expect(mockStepperService.cleanup).toHaveBeenCalled();
    });
  });
});
