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
import { of, Subject } from 'rxjs';
import { MappingStepperComponent } from './mapping-stepper.component';
import { MappingStepperService } from '../service/mapping-stepper.service';
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
        'expandTemplates',
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

    it('injects the mocked services (component-level providers are overridden)', () => {
      expect(component['stepperService']).toBe(mockStepperService);
      expect(component['sharedService']).toBe(mockSharedService);
    });

    it('builds the view model and template form on ngOnInit', async () => {
      await component.ngOnInit();
      expect(component.stepperViewModel).toBeDefined();
      expect(component.templateForm).toBeDefined();
      expect(component.templateForm.get('extensionName')).toBeTruthy();
      expect(component.templateForm.get('eventName')).toBeTruthy();
    });

    it('loads features, service configuration and AI-agent status on init', async () => {
      await component.ngOnInit();
      expect(mockSharedService.getFeatures).toHaveBeenCalled();
      expect(mockSharedService.getServiceConfiguration).toHaveBeenCalled();
      expect(mockStepperService.checkAIAgentDeployment).toHaveBeenCalled();
    });

    it('sets source/target systems from direction (INBOUND)', async () => {
      component.mapping = buildMapping({ direction: Direction.INBOUND });
      await component.ngOnInit();
      expect(component.sourceSystem).toBe('Broker');
      expect(component.targetSystem).toBe('Cumulocity');
    });

    it('sets source/target systems from direction (OUTBOUND)', async () => {
      component.mapping = buildMapping({ direction: Direction.OUTBOUND });
      await component.ngOnInit();
      expect(component.sourceSystem).toBe('Cumulocity');
      expect(component.targetSystem).toBe('Broker');
    });

    it('resolves source/target JSON schemas', async () => {
      await component.ngOnInit();
      expect(component.schemaSource).toBeDefined();
      expect(component.schemaTarget).toBeDefined();
    });

    it('sets code-editor help/label text for Smart Function mappings', async () => {
      component.mapping = buildMapping({ transformationType: TransformationType.SMART_FUNCTION });
      await component.ngOnInit();
      expect(component.codeEditorLabel).toContain('Smart functions');
    });

    it('makes editors read-only when the user lacks admin and create roles', async () => {
      mockSharedService.getFeatures.and.returnValue(
        Promise.resolve({ ...mockFeature, userHasMappingAdminRole: false, userHasMappingCreateRole: false })
      );
      await component.ngOnInit();
      expect(component.editorOptionsSourceTemplate.readOnly).toBe(true);
      expect(component.editorOptionsTargetTemplate.readOnly).toBe(true);
    });
  });

  describe('YAML <-> configuration helpers', () => {
    it('serialises a configuration object to YAML', () => {
      const yaml = component.configurationToYaml({ host: 'localhost', port: 1883 });
      expect(yaml).toContain('host: localhost');
      expect(yaml).toContain('port: 1883');
    });

    it('returns an empty string for an undefined configuration', () => {
      expect(component.configurationToYaml(undefined)).toBe('');
    });

    it('parses YAML back to a configuration object', () => {
      expect(component.yamlToConfiguration('host: localhost\nport: 1883')).toEqual({
        host: 'localhost',
        port: 1883
      });
    });

    it('returns undefined for blank YAML', () => {
      expect(component.yamlToConfiguration('   ')).toBeUndefined();
      expect(component.yamlToConfiguration('')).toBeUndefined();
    });

    it('returns undefined for YAML that is not an object (scalar)', () => {
      expect(component.yamlToConfiguration('just-a-scalar')).toBeUndefined();
    });

    it('returns undefined for invalid YAML instead of throwing', () => {
      expect(component.yamlToConfiguration('key: : : bad')).toBeUndefined();
    });
  });

  describe('Extension selection', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('creates the extension object and stores the name', () => {
      component.mapping = buildMapping();
      delete component.mapping.extension;
      component.onSelectExtensionName('my-extension');

      expect(component.mapping.extension?.extensionName).toBe('my-extension');
      expect(mockStepperService.selectExtensionName).toHaveBeenCalledWith(
        'my-extension',
        component.extensions,
        component.mapping
      );
    });

    it('copies the matching event entry and flags the parameter block on event selection', () => {
      const eventEntry = {
        eventName: 'evt',
        extensionType: 'SOURCE',
        direction: Direction.INBOUND,
        fqnClassName: 'com.acme.Ext',
        loaded: true,
        message: 'ok',
        parameter: { foo: 'bar' }
      };
      const extension = { extensionEntries: { evt: eventEntry } } as unknown as Extension;
      component.extensions = new Map([['my-extension', extension]]);
      component.mapping.extension = { extensionName: 'my-extension' } as any;

      component.onSelectExtensionEvent('evt');

      expect(component.mapping.extension.eventName).toBe('evt');
      expect(component.mapping.extension.fqnClassName).toBe('com.acme.Ext');
      expect(component.hasExtensionParameter).toBe(true);
      // Pre-fills the parameter when none was set yet
      expect(component.mapping.extension.parameter).toEqual({ foo: 'bar' });
    });

    it('marks no parameter block when the matched event has none', () => {
      const eventEntry = { eventName: 'evt', extensionType: 'SOURCE' };
      const extension = { extensionEntries: { evt: eventEntry } } as unknown as Extension;
      component.extensions = new Map([['my-extension', extension]]);
      component.mapping.extension = { extensionName: 'my-extension' } as any;

      component.onSelectExtensionEvent('evt');

      expect(component.hasExtensionParameter).toBe(false);
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

    it('does nothing when the selected code template is unknown', () => {
      component.templateId = 'missing' as any;
      component.mappingCode = 'untouched';
      component.onSelectCodeTemplate();
      expect(component.mappingCode).toBe('untouched');
    });

    it('loads the selected code template into mappingCode', () => {
      component.codeTemplatesDecoded.set('t1', { code: 'function onMessage() {}' } as any);
      component.templateId = 't1' as any;
      component.serviceConfiguration = { supportESM: false } as any;

      component.onSelectCodeTemplate();

      expect(component.mappingCode).toContain('function onMessage() {}');
    });

    it('appends an ESM export for Smart Functions when Support ESM is enabled', () => {
      component.mapping = buildMapping({ transformationType: TransformationType.SMART_FUNCTION });
      component.codeTemplatesDecoded.set('t1', { code: 'function onMessage() {}' } as any);
      component.templateId = 't1' as any;
      component.serviceConfiguration = { supportESM: true } as any;

      component.onSelectCodeTemplate();

      expect(component.mappingCode).toContain('export { onMessage };');
    });

    it('does not duplicate an ESM export that is already present', () => {
      component.mapping = buildMapping({ transformationType: TransformationType.SMART_FUNCTION });
      component.codeTemplatesDecoded.set('t1', {
        code: 'function onMessage() {}\nexport { onMessage };'
      } as any);
      component.templateId = 't1' as any;
      component.serviceConfiguration = { supportESM: true } as any;

      component.onSelectCodeTemplate();

      expect(component.mappingCode!.match(/export \{ onMessage \}/g)?.length).toBe(1);
    });
  });

  describe('Target API changes', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('refreshes templates and the target schema for INBOUND', async () => {
      component.stepperConfiguration.direction = Direction.INBOUND;
      await component.onTargetAPIChanged('EVENT');
      expect(component.mapping.targetTemplate).toBeDefined();
      expect(component.mapping.sourceTemplate).toBeDefined();
      expect(component.schemaTarget).toBeDefined();
    });

    it('refreshes templates and the source schema for OUTBOUND', async () => {
      component.mapping = buildMapping({ direction: Direction.OUTBOUND });
      component.stepperConfiguration.direction = Direction.OUTBOUND;
      await component.onTargetAPIChanged('EVENT');
      expect(component.mapping.sourceTemplate).toBeDefined();
      expect(component.schemaSource).toBeDefined();
    });
  });

  describe('Step transitions', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('updates the current index and revalidates substitutions on every step change', async () => {
      await component.onStepChange({ selectedIndex: STEP_SELECT_TEMPLATES });
      expect(component.currentStepIndex).toBe(STEP_SELECT_TEMPLATES);
      expect(mockStepperService.updateSubstitutionValidity).toHaveBeenCalledWith(
        component.mapping,
        component.stepperConfiguration.allowNoDefinedIdentifier,
        STEP_SELECT_TEMPLATES,
        component.stepperConfiguration.showCodeEditor
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

  describe('Commit & cancel', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('emits cancel', (done) => {
      component.cancel.subscribe(() => done());
      component.onCancelButton();
    });

    it('serialises templates as JSON strings and emits the mapping on commit (no expansion)', (done) => {
      component.stepperConfiguration.allowTemplateExpansion = false;
      component.sourceTemplate = { a: 1 };
      component.targetTemplate = { b: 2 };

      component.commit.subscribe((mapping: Mapping) => {
        expect(mapping).toBe(component.mapping);
        expect(mapping.sourceTemplate).toBe(JSON.stringify({ a: 1 }));
        expect(mapping.targetTemplate).toBe(JSON.stringify({ b: 2 }));
        done();
      });

      component.onCommitButton();
    });

    it('encodes mappingCode to base64 on commit', (done) => {
      component.sourceTemplate = {};
      component.targetTemplate = {};
      component.mappingCode = 'function onMessage() {}';

      component.commit.subscribe((mapping: Mapping) => {
        expect(mapping.code).toBeTruthy();
        // base64 round-trips back to the original code (sans metadata tags)
        expect(atob(mapping.code!)).toContain('function onMessage');
        done();
      });

      component.onCommitButton();
    });
  });

  describe('Alert management', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('adds an alert via the alert service', () => {
      const alert = { type: 'danger', text: 'boom' } as any;
      component.raiseAlert(alert);
      expect(mockAlertService.add).toHaveBeenCalledWith(alert);
    });

    it('removes pre-existing info/warning alerts before adding a new one', () => {
      const infoAlert = { type: 'info', text: 'info' } as any;
      const warnAlert = { type: 'warning', text: 'warn' } as any;
      Object.defineProperty(mockAlertService, 'state', {
        get: () => [infoAlert, warnAlert],
        configurable: true
      });

      component.raiseAlert({ type: 'danger', text: 'new' } as any);

      expect(mockAlertService.remove).toHaveBeenCalledWith(infoAlert);
      expect(mockAlertService.remove).toHaveBeenCalledWith(warnAlert);
    });

    it('clears info/warning alerts on clearAlerts', () => {
      const infoAlert = { type: 'info', text: 'info' } as any;
      Object.defineProperty(mockAlertService, 'state', {
        get: () => [infoAlert],
        configurable: true
      });
      component.clearAlerts();
      expect(mockAlertService.remove).toHaveBeenCalledWith(infoAlert);
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
