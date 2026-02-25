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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ChangeDetectorRef } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { BsModalService } from 'ngx-bootstrap/modal';
import { of, Subject } from 'rxjs';
import { Content } from 'vanilla-jsoneditor';
import { MappingStepperComponent } from './mapping-stepper.component';
import { MappingStepperService } from '../service/mapping-stepper.service';
import { SubstitutionManagementService } from '../service/substitution-management.service';
import { SharedService } from '../../shared';
import { AlertService, BottomDrawerService } from '@c8y/ngx-components';
import {
  Direction,
  Extension,
  Mapping,
  StepperConfiguration,
  TransformationType,
  MappingType,
  SnoopStatus,
  DeploymentMapEntry,
  Feature,
  Qos,
  RepairStrategy
} from '../../shared';
import { EditorMode, STEP_GENERAL_SETTINGS, STEP_SELECT_TEMPLATES, STEP_DEFINE_SUBSTITUTIONS, STEP_TEST_MAPPING } from '../shared/stepper.model';

describe('MappingStepperComponent', () => {
  let component: MappingStepperComponent;
  let fixture: ComponentFixture<MappingStepperComponent>;
  let mockStepperService: jasmine.SpyObj<MappingStepperService>;
  let mockSubstitutionService: jasmine.SpyObj<SubstitutionManagementService>;
  let mockSharedService: jasmine.SpyObj<SharedService>;
  let mockAlertService: jasmine.SpyObj<AlertService>;
  let mockBottomDrawerService: jasmine.SpyObj<BottomDrawerService>;
  let mockBsModalService: jasmine.SpyObj<BsModalService>;
  let mockCdr: jasmine.SpyObj<ChangeDetectorRef>;

  const mockMapping: Mapping = {
    id: '1',
    identifier: 'test-mapping',
    name: 'Test Mapping',
    direction: Direction.INBOUND,
    targetAPI: 'MEASUREMENT',
    mappingType: MappingType.JSON,
    transformationType: TransformationType.DEFAULT,
    substitutions: [],
    snoopedTemplates: [],
    snoopStatus: SnoopStatus.NONE,
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
    lastUpdate: Date.now()
  };

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
    deviceIsolationMQTTServiceEnabled: false
  };

  beforeEach(async () => {
    // Create spy objects for all dependencies
    mockStepperService = jasmine.createSpyObj('MappingStepperService', [
      'loadExtensions',
      'selectExtensionName',
      'updateSubstitutionValidity',
      'expandTemplates',
      'expandExistingTemplates',
      'parseSnoopedTemplate',
      'evaluateFilterExpression',
      'checkAIAgentDeployment',
      'loadCodeTemplates',
      'createCodeTemplate',
      'cleanup'
    ], {
      countDeviceIdentifiers$: of(0),
      isSubstitutionValid$: of(true),
      isContentChangeValid$: new Subject(),
      extensionEvents$: of([]),
      isButtonDisabled$: new Subject(),
      sourceCustomMessage$: of(''),
      targetCustomMessage$: of('')
    });

    mockSubstitutionService = jasmine.createSpyObj('SubstitutionManagementService', [
      'isSubstitutionValid',
      'addSubstitution'
    ]);

    mockSharedService = jasmine.createSpyObj('SharedService', [
      'getFeatures',
      'getServiceConfiguration',
      'getCodeTemplates'
    ]);

    mockAlertService = jasmine.createSpyObj('AlertService', [
      'add',
      'remove',
      'clearAll',
      'success',
      'danger'
    ], {
      state: []
    });

    mockBottomDrawerService = jasmine.createSpyObj('BottomDrawerService', [
      'openDrawer'
    ]);

    mockBsModalService = jasmine.createSpyObj('BsModalService', [
      'show'
    ]);

    mockCdr = jasmine.createSpyObj('ChangeDetectorRef', [
      'markForCheck',
      'detectChanges'
    ]);

    // Setup default return values
    mockStepperService.loadExtensions.and.returnValue(Promise.resolve(new Map<string, Extension>()));
    mockStepperService.expandTemplates.and.returnValue({
      sourceTemplate: {},
      targetTemplate: {}
    });
    mockStepperService.expandExistingTemplates.and.returnValue({
      sourceTemplate: {},
      targetTemplate: {}
    });
    mockStepperService.evaluateFilterExpression.and.returnValue(Promise.resolve({
      result: '',
      resultType: 'empty',
      valid: true
    }));
    mockStepperService.checkAIAgentDeployment.and.returnValue(Promise.resolve({
      aiAgent: null,
      aiAgentDeployed: false
    }));
    mockStepperService.loadCodeTemplates.and.returnValue(Promise.resolve(new Map()));
    mockSharedService.getFeatures.and.returnValue(Promise.resolve(mockFeature));
    mockSharedService.getServiceConfiguration.and.returnValue(Promise.resolve({} as any));
    mockSharedService.getCodeTemplates.and.returnValue(Promise.resolve({} as any));

    await TestBed.configureTestingModule({
      imports: [
        MappingStepperComponent,
        ReactiveFormsModule
      ],
      providers: [
        { provide: MappingStepperService, useValue: mockStepperService },
        { provide: SubstitutionManagementService, useValue: mockSubstitutionService },
        { provide: SharedService, useValue: mockSharedService },
        { provide: AlertService, useValue: mockAlertService },
        { provide: BottomDrawerService, useValue: mockBottomDrawerService },
        { provide: BsModalService, useValue: mockBsModalService },
        { provide: ChangeDetectorRef, useValue: mockCdr }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(MappingStepperComponent);
    component = fixture.componentInstance;

    // Set required inputs
    component.mapping = mockMapping;
    component.stepperConfiguration = mockStepperConfiguration;
    component.deploymentMapEntry = mockDeploymentMapEntry;
  });

  describe('Component Initialization', () => {
    it('should create the component', () => {
      expect(component).toBeTruthy();
    });

    it('should initialize view model on ngOnInit', async () => {
      await component.ngOnInit();
      expect(component.stepperViewModel).toBeDefined();
    });

    it('should load extensions and features on init', async () => {
      await component.ngOnInit();
      expect(mockSharedService.getFeatures).toHaveBeenCalled();
      expect(mockSharedService.getServiceConfiguration).toHaveBeenCalled();
      expect(mockStepperService.checkAIAgentDeployment).toHaveBeenCalled();
    });

    it('should initialize template form with correct controls', async () => {
      await component.ngOnInit();
      expect(component.templateForm).toBeDefined();
      expect(component.templateForm.get('extensionName')).toBeDefined();
      expect(component.templateForm.get('eventName')).toBeDefined();
      expect(component.templateForm.get('snoopedTemplateIndex')).toBeDefined();
    });

    it('should set correct source and target systems based on direction', async () => {
      component.mapping.direction = Direction.INBOUND;
      await component.ngOnInit();
      expect(component.targetSystem).toBe('Cumulocity');
      expect(component.sourceSystem).toBe('Broker');

      component.mapping.direction = Direction.OUTBOUND;
      await component.ngOnInit();
      expect(component.targetSystem).toBe('Broker');
      expect(component.sourceSystem).toBe('Cumulocity');
    });
  });

  describe('Step Transitions', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('should handle transition to general settings step', async () => {
      const event = { selectedIndex: STEP_GENERAL_SETTINGS };
      await component.onStepChange(event);

      expect(component.currentStepIndex).toBe(STEP_GENERAL_SETTINGS);
      expect(mockStepperService.loadExtensions).toHaveBeenCalledWith(mockMapping);
    });

    it('should handle transition to select templates step', async () => {
      const event = { selectedIndex: STEP_SELECT_TEMPLATES };
      component.stepperForward = true;
      component.mapping.filterMapping = 'test-filter';

      await component.onStepChange(event);

      expect(component.currentStepIndex).toBe(STEP_SELECT_TEMPLATES);
      expect(mockStepperService.evaluateFilterExpression).toHaveBeenCalled();
    });

    it('should handle transition to define substitutions step', async () => {
      const event = { selectedIndex: STEP_DEFINE_SUBSTITUTIONS };
      component.sourceTemplate = { test: 'source' };
      component.targetTemplate = { test: 'target' };

      await component.onStepChange(event);

      expect(component.currentStepIndex).toBe(STEP_DEFINE_SUBSTITUTIONS);
      expect(mockStepperService.updateSubstitutionValidity).toHaveBeenCalled();
    });

    it('should handle transition to test mapping step', async () => {
      const event = { selectedIndex: STEP_TEST_MAPPING };
      component.mappingCode = 'test-code';
      component.sourceTemplate = { test: 'source' };
      component.targetTemplate = { test: 'target' };

      await component.onStepChange(event);

      expect(component.currentStepIndex).toBe(STEP_TEST_MAPPING);
    });

    it('should update substitution validity on each step change', async () => {
      const event = { selectedIndex: STEP_SELECT_TEMPLATES };
      await component.onStepChange(event);

      expect(mockStepperService.updateSubstitutionValidity).toHaveBeenCalledWith(
        mockMapping,
        mockStepperConfiguration.allowNoDefinedIdentifier,
        STEP_SELECT_TEMPLATES,
        mockStepperConfiguration.showCodeEditor
      );
    });
  });

  describe('Subscription Management', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('should subscribe to extension name changes', (done) => {
      const extensionName = 'test-extension';
      component.templateForm.get('extensionName')?.setValue(extensionName);

      setTimeout(() => {
        expect(mockStepperService.selectExtensionName).toHaveBeenCalledWith(
          extensionName,
          component.extensions,
          mockMapping
        );
        done();
      }, 150); // Wait for debounce
    });

    it('should subscribe to event name changes', (done) => {
      const eventName = 'test-event';
      component.mapping.extension = { extensionName: 'test-ext' } as any;

      component.templateForm.get('eventName')?.setValue(eventName);

      setTimeout(() => {
        expect(component.mapping.extension?.eventName).toBe(eventName);
        done();
      }, 150); // Wait for debounce
    });

    it('should unsubscribe on component destroy', () => {
      spyOn(component['destroy$'], 'next');
      spyOn(component['destroy$'], 'complete');

      component.ngOnDestroy();

      expect(component['destroy$'].next).toHaveBeenCalled();
      expect(component['destroy$'].complete).toHaveBeenCalled();
      expect(mockStepperService.cleanup).toHaveBeenCalled();
    });

    it('should handle isSubstitutionValid$ subscription', (done) => {
      const validSubject = mockStepperService.isSubstitutionValid$ as Subject<boolean>;

      component.ngOnInit().then(() => {
        validSubject.next(false);

        setTimeout(() => {
          expect(component.templateForm.errors).toEqual({ 'incorrect': true });

          validSubject.next(true);
          setTimeout(() => {
            expect(component.templateForm.errors).toBeNull();
            done();
          }, 10);
        }, 10);
      });
    });
  });

  describe('Template Changes', () => {
    beforeEach(async () => {
      await component.ngOnInit();
      component.sourceTemplate = { test: 'value' };
      component.targetTemplate = { test: 'value' };
    });

    it('should handle source template changes with valid JSON', () => {
      const contentChanges = {
        previousContent: { json: { test: 'value' } } as Content,
        updatedContent: { json: { newTest: 'value' } } as Content
      };

      component.onSourceTemplateChanged(contentChanges);

      expect(component.sourceTemplateUpdated).toEqual({ newTest: 'value' });
    });

    it('should handle source template changes with text content', () => {
      const contentChanges = {
        previousContent: { json: { test: 'value' } } as Content,
        updatedContent: { text: '{"newTest": "value"}' } as Content
      };

      component.onSourceTemplateChanged(contentChanges);

      expect(component.sourceTemplateUpdated).toEqual({ newTest: 'value' });
    });

    it('should handle invalid JSON during editing', () => {
      const contentChanges = {
        previousContent: { json: { test: 'value' } } as Content,
        updatedContent: { text: '{invalid json' } as Content
      };

      component.onSourceTemplateChanged(contentChanges);

      // Should allow invalid JSON during editing
      expect(component.sourceTemplateUpdated).toBeDefined();
    });

    it('should handle target template changes', () => {
      const contentChanges = {
        previousContent: { json: { test: 'value' } } as Content,
        updatedContent: { json: { newTest: 'value' } } as Content
      };

      component.onTargetTemplateChanged(contentChanges);

      expect(component.targetTemplateUpdated).toEqual({ newTest: 'value' });
    });
  });

  describe('Event Emitters', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('should emit cancel event', (done) => {
      component.cancel.subscribe(() => {
        expect(true).toBe(true);
        done();
      });

      component.onCancelButton();
    });

    it('should emit commit event with updated mapping', (done) => {
      component.sourceTemplate = { test: 'source' };
      component.targetTemplate = { test: 'target' };
      component.mappingCode = 'test-code';

      component.commit.subscribe((mapping: Mapping) => {
        expect(mapping).toBe(mockMapping);
        expect(mapping.code).toBeDefined();
        done();
      });

      component.onCommitButton();
    });

    it('should emit updateSourceEditor event on editor initialization', () => {
      spyOn(component.updateSourceEditor, 'emit');

      component.onEditorSourceInitialized();

      expect(component.updateSourceEditor.emit).toHaveBeenCalled();
    });

    it('should emit updateTargetEditor event on editor initialization', () => {
      spyOn(component.updateTargetEditor, 'emit');

      component.onEditorTargetInitialized();

      expect(component.updateTargetEditor.emit).toHaveBeenCalled();
    });
  });

  describe('Filter Expression Evaluation', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('should update filter expression result on path change', async () => {
      const path = '$.test.path';
      const expectedResult = {
        result: 'test-result',
        resultType: 'string',
        valid: true
      };

      mockStepperService.evaluateFilterExpression.and.returnValue(Promise.resolve(expectedResult));

      await component.updateFilterExpressionResult(path);

      expect(mockStepperService.evaluateFilterExpression).toHaveBeenCalled();
      expect(component.filterModel.filterExpression).toEqual(expectedResult);
      expect(component.mapping.filterMapping).toBe(path);
    });

    it('should handle filter expression evaluation errors', async () => {
      const path = '$.invalid';
      mockStepperService.evaluateFilterExpression.and.returnValue(
        Promise.reject(new Error('Invalid expression'))
      );

      await component.updateFilterExpressionResult(path);

      expect(component.filterModel.filterExpression?.valid).toBe(false);
    });

    it('should clear alerts before evaluating filter expression', async () => {
      await component.updateFilterExpressionResult('$.test');

      expect(mockAlertService.clearAll).toHaveBeenCalled();
    });
  });

  describe('Code Template Management', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('should select code template and update mappingCode', () => {
      const mockTemplate = {
        id: 'test-id',
        code: 'test-code',
        name: 'Test',
        templateType: 'INBOUND_DEFAULT' as any,
        internal: false,
        readonly: false,
        defaultTemplate: false
      };
      component.codeTemplatesDecoded.set('test-id', mockTemplate);
      component.templateId = 'test-id' as any;

      component.onSelectCodeTemplate();

      expect(component.mappingCode).toBe('test-code');
    });

    it('should handle code value changes', () => {
      const newCode = 'new-code-value';

      component.onValueCodeChange(newCode);

      expect(component.mappingCode).toBe(newCode);
    });
  });

  describe('Substitution Selection', () => {
    beforeEach(async () => {
      await component.ngOnInit();
      component.mapping.substitutions = [
        {
          pathSource: '$.source',
          pathTarget: '$.target',
          repairStrategy: RepairStrategy.DEFAULT,
          expandArray: false
        }
      ];
    });

    it('should select substitution by index', async () => {
      await component.onSelectSubstitution(0);

      expect(component.selectedSubstitution).toBe(0);
      expect(component.substitutionModel.pathSource).toBe('$.source');
      expect(component.substitutionModel.pathTarget).toBe('$.target');
    });

    it('should not select invalid substitution index', async () => {
      const initialSelection = component.selectedSubstitution;

      await component.onSelectSubstitution(-1);
      expect(component.selectedSubstitution).toBe(initialSelection);

      await component.onSelectSubstitution(999);
      expect(component.selectedSubstitution).toBe(initialSelection);
    });
  });

  describe('Alert Management', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('should raise alert', () => {
      const alert = { type: 'warning', text: 'Test warning' } as any;

      component.raiseAlert(alert);

      expect(mockAlertService.add).toHaveBeenCalledWith(alert);
    });

    it('should clear all alerts', () => {
      component.clearAlerts();

      expect(mockAlertService.clearAll).toHaveBeenCalled();
    });

    it('should remove info alerts before adding new alert', () => {
      const infoAlert = { type: 'info', text: 'Info alert' } as any;
      const warningAlert = { type: 'warning', text: 'Warning alert' } as any;

      // Mock the state getter to return alerts
      Object.defineProperty(mockAlertService, 'state', {
        get: () => [infoAlert, warningAlert],
        configurable: true
      });

      component.raiseAlert({ type: 'danger', text: 'New alert' } as any);

      expect(mockAlertService.remove).toHaveBeenCalledWith(infoAlert);
    });
  });

  describe('Deployment Map Entry Changes', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('should disable button when no connectors', (done) => {
      const entryWithoutConnectors = { identifier: 'test', connectors: [] };

      component.deploymentMapEntryChange(entryWithoutConnectors);

      // Wait for queueMicrotask
      queueMicrotask(() => {
        expect(mockCdr.markForCheck).toHaveBeenCalled();
        done();
      });
    });

    it('should enable button when connectors exist', (done) => {
      const entryWithConnectors = { identifier: 'test', connectors: ['conn-1'] };

      component.deploymentMapEntryChange(entryWithConnectors);

      // Wait for queueMicrotask
      queueMicrotask(() => {
        expect(mockCdr.markForCheck).toHaveBeenCalled();
        done();
      });
    });
  });
});
