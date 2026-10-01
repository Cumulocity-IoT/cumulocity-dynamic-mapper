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
import { SubscriptionService } from '../core/subscription.service';
import { MappingUnifiedEditorComponent } from './mapping-unified-editor.component';
import {
  CommitBlocker,
  CommitResult,
  EditorSessionResult,
  MappingStepperService
} from '../service/mapping-stepper.service';
import { MappingValidationError } from '../../shared/mapping/mapping-validation-error';
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
  Qos
} from '../../shared';
import { EditorMode } from '../../shared/mapping/stepper.model';
import { captureMappingContentSnapshot, configurationToYaml, yamlToConfiguration } from '../../shared/mapping/util';

// Tab indices (mirrors the private constants in the component under test)
const TAB_CONNECTOR = 0;
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
  let mockSubscriptionService: jasmine.SpyObj<SubscriptionService>;
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

  // Default stand-in for MappingStepperService.initializeEditorSession()'s return value — the
  // real logic behind it is unit tested directly on the service (mapping-stepper.service.spec.ts);
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
    isButtonDisabled$ = new Subject<boolean>();
    isSubstitutionValid$ = new Subject<boolean>();
    mappingPropertyChanged$ = new Subject<Mapping>();

    mockStepperService = jasmine.createSpyObj(
      'MappingStepperService',
      [
        'loadExtensions',
        'selectExtensionName',
        'updateSubstitutionValidity',
        'refreshSubstitutionValidity',
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
        'encodeMappingForCommit',
        'commitMapping'
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
      'saveDraft',
      'createMapping',
      'refreshMappings',
      'updateDefinedDeploymentMapEntry'
    ]);
    mockRouter = jasmine.createSpyObj('Router', ['navigateByUrl'], { url: '/mappings/inbound/edit/42' });
    mockGlobalContextService = jasmine.createSpyObj('GlobalContextService', ['register', 'unregister']);
    mockSubscriptionService = jasmine.createSpyObj('SubscriptionService', ['validateSubscriptionOutbound']);
    mockSubscriptionService.validateSubscriptionOutbound.and.returnValue(Promise.resolve(undefined as any));
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
    mockStepperService.initializeEditorSession.and.returnValue(Promise.resolve(buildEditorSessionResult()));
    mockStepperService.registerCompletionProvider.and.returnValue(Promise.resolve());
    mockSharedService.getFeatures.and.returnValue(Promise.resolve(mockFeature));
    mockSharedService.getServiceConfiguration.and.returnValue(Promise.resolve({} as any));
    mockSharedService.getCodeTemplates.and.returnValue(Promise.resolve({} as any));
    mockMappingService.saveDraft.and.returnValue(Promise.resolve(buildMapping()));
    mockMappingService.createMapping.and.returnValue(Promise.resolve(buildMapping()));
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
        { provide: GlobalContextService, useValue: mockGlobalContextService },
        // The component injects SubscriptionService, whose real constructor takes a
        // FetchClient the test injector cannot provide (NG0201). It is only used for
        // validateSubscriptionOutbound(), so a spy is enough.
        { provide: SubscriptionService, useValue: mockSubscriptionService }
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

    // The real bootstrap logic (view model, systems, schemas, read-only gating, code-editor help
    // text, ...) moved into MappingStepperService.initializeEditorSession() (Phase 4) and is unit
    // tested directly there (mapping-stepper.service.spec.ts). These tests only verify the
    // component delegates to it and applies the result back onto its own fields.
    it('delegates to initializeEditorSession with its own mapping/config/destroy$/callbacks', async () => {
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
        })
        // No 5th argument — unlike the stepper, the unified editor uses the default
        // (disableExtensionSelectorsWhenHidden = false).
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
      component.mapping = buildMapping({ transformationType: TransformationType.SUBSTITUTION_AS_CODE });
      component.stepperConfiguration = buildConfig();
      expect(component.isTabVisible(TAB_TEST_MAPPING)).toBe(false);
    });
  });

  // configurationToYaml/yamlToConfiguration moved to mapping/shared/util.ts (shared with
  // MappingStepperComponent) — see util.spec.ts for their unit tests.
  describe('YAML <-> configuration helpers', () => {
    it('round-trips a configuration object through the shared util functions', () => {
      const yaml = configurationToYaml({ a: 1 });
      expect(yamlToConfiguration(yaml)).toEqual({ a: 1 });
    });
  });

  // ESM-export detection itself moved to MappingStepperService.computeCodeFromTemplate
  // (Phase 3) and is unit tested there; this only verifies the component applies the service's
  // result.
  describe('Code template selection', () => {
    it('applies the code computed by the service', () => {
      component.mapping = buildMapping();
      mockStepperService.computeCodeFromTemplate.and.returnValue('function onMessage() { /* with export */ }');

      component.onSelectCodeTemplate();

      expect(mockStepperService.computeCodeFromTemplate).toHaveBeenCalledWith(
        component.codeTemplatesDecoded, component.templateId, component.serviceConfiguration, component.mapping.transformationType
      );
      expect(component.mappingCode).toBe('function onMessage() { /* with export */ }');
    });

    it('leaves mappingCode untouched when the service reports no template selected', () => {
      component.mapping = buildMapping();
      component.mappingCode = 'untouched';
      mockStepperService.computeCodeFromTemplate.and.returnValue(undefined);

      component.onSelectCodeTemplate();

      expect(component.mappingCode).toBe('untouched');
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
      expect(mockStepperService.refreshSubstitutionValidity).toHaveBeenCalled();
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

  /**
   * The persistence sequence itself (encode, saveDraft vs createMapping, deployment write,
   * refresh) moved to {@link MappingStepperService.commitMapping} in Phase 6 and is unit tested
   * there against the full CommitResult matrix (mapping-stepper.service.spec.ts). What remains
   * here is the unified editor's OWN responsibility: assembling the request, and turning each
   * result status into navigation, an alert, or a tab.
   */
  describe('onCommitButton', () => {
    function commitResolvesTo(result: CommitResult): void {
      mockStepperService.commitMapping.and.resolveTo(result);
    }

    /** A successful commit, carrying the server's copy as `persisted`. */
    function saved(
      contentChanged = true,
      deploymentChanged = false,
      persisted: Mapping = component.mapping
    ): CommitResult {
      return { status: 'saved', contentChanged, deploymentChanged, persisted };
    }

    beforeEach(() => {
      component.mapping = buildMapping();
      component.stepperConfiguration = buildConfig({ allowTemplateExpansion: false });
      component.deploymentMapEntry = deploymentMapEntry;
      component.sourceTemplate = { a: 1 };
      component.targetTemplate = { b: 2 };
      component.stepperViewModel = { showExtensionSelectorsSource: false, showExtensionSelectorsTarget: false } as any;
      component.templateForm = new FormGroup({
        extensionName: new FormControl(''),
        eventName: new FormControl('')
      });
      commitResolvesTo(saved());
    });

    it('passes the editor state, including both forms, to commitMapping', async () => {
      // Read before the call: a successful commit re-baselines these, replacing the objects.
      const snapshotAtCallTime = component['initialContentSnapshot'];

      await component.onCommitButton();

      expect(mockStepperService.commitMapping).toHaveBeenCalledWith(jasmine.objectContaining({
        mapping: component.mapping,
        deploymentMapEntry,
        stepperConfiguration: component.stepperConfiguration,
        sourceTemplate: { a: 1 },
        targetTemplate: { b: 2 },
        mappingCode: component.mappingCode,
        initialContentSnapshot: snapshotAtCallTime,
        forms: jasmine.objectContaining({
          propertyFormly: component.propertyFormly,
          templateForm: component.templateForm,
          validateExtensionSelection: false
        })
      }));
    });

    it('asks commitMapping to validate the extension selection when a selector is rendered', async () => {
      component.stepperViewModel = { showExtensionSelectorsSource: true, showExtensionSelectorsTarget: false } as any;

      await component.onCommitButton();

      expect(mockStepperService.commitMapping.calls.mostRecent().args[0].forms?.validateExtensionSelection).toBe(true);
    });

    // Saving deliberately keeps the editor open so the user can carry on to the Testing tab.
    // Leaving is Cancel's job.
    it('alerts and stays in the editor on a successful save', async () => {
      await component.onCommitButton();

      // add() rather than success(): the toast needs an explicit timeout, because Cumulocity
      // auto-dismisses a detail-less success alert after 3s.
      expect(mockAlertService.add).toHaveBeenCalled();
      const alert = mockAlertService.add.calls.mostRecent().args[0];
      expect(alert.type).toBe('success');
      expect(alert.timeout).toBeGreaterThan(3000);
      expect(mockRouter.navigateByUrl).not.toHaveBeenCalled();
    });

    it('stays in the editor without an alert when an update changed nothing', async () => {
      commitResolvesTo(saved(false, false));

      await component.onCommitButton();

      expect(mockAlertService.success).not.toHaveBeenCalled();
      expect(mockAlertService.add).not.toHaveBeenCalled();
      expect(mockRouter.navigateByUrl).not.toHaveBeenCalled();
    });

    it('adopts the lastUpdate the server issued, so the next save is not a conflict', async () => {
      component.mapping.lastUpdate = 1000;
      commitResolvesTo(saved(true, false, { ...component.mapping, lastUpdate: 2000 } as Mapping));

      await component.onCommitButton();

      expect(component.mapping.lastUpdate).toBe(2000);
    });

    it('re-baselines so an immediate second save writes neither a draft nor the deployment', async () => {
      await component.onCommitButton();
      mockStepperService.commitMapping.calls.reset();

      await component.onCommitButton();

      const second = mockStepperService.commitMapping.calls.mostRecent().args[0];
      // Same snapshot values the service compares against => contentChanged/deploymentChanged false.
      expect(second.initialDeploymentConnectors).toBe(JSON.stringify(deploymentMapEntry.connectors));
      expect(second.initialContentSnapshot).toEqual(
        jasmine.objectContaining({ mappingJson: JSON.stringify(component.mapping) })
      );
    });

    it('jumps to the tab that owns a blocked precondition, and stays put', async () => {
      const cases: [CommitBlocker, number][] = [
        [CommitBlocker.NO_CONNECTOR, TAB_CONNECTOR],
        [CommitBlocker.MAPPING_TOPIC, TAB_GENERAL_SETTINGS],
        [CommitBlocker.PROPERTY_FORM, TAB_GENERAL_SETTINGS],
        [CommitBlocker.EXTENSION_SELECTION, TAB_SELECT_TEMPLATES]
      ];

      for (const [blocker, tab] of cases) {
        component.activeTabIndex = TAB_TEST_MAPPING;
        commitResolvesTo({ status: 'blocked', blocker });

        await component.onCommitButton();

        expect(component.activeTabIndex).withContext(blocker).toBe(tab);
      }
      expect(mockRouter.navigateByUrl).not.toHaveBeenCalled();
    });

    it('raises the blocker message as a warning when one is supplied', async () => {
      commitResolvesTo({ status: 'blocked', blocker: CommitBlocker.ENCODING, message: 'Internal error in editor. Try again!' });

      await component.onCommitButton();

      expect(mockStepperService.raiseAlert).toHaveBeenCalledWith({ type: 'warning', text: 'Internal error in editor. Try again!' });
      // ENCODING has no tab of its own — the editor stays where it is.
      expect(mockRouter.navigateByUrl).not.toHaveBeenCalled();
    });

    it('opens the validation drawer and stays open on a rejected save', async () => {
      const error = new MappingValidationError('invalid', ['e1'], []);
      commitResolvesTo({ status: 'rejected', error });
      mockBottomDrawerService.openDrawer.and.returnValue({
        instance: { result: Promise.resolve({ action: 'close' }) }
      } as any);

      await component.onCommitButton();

      expect(mockBottomDrawerService.openDrawer).toHaveBeenCalled();
      expect(mockRouter.navigateByUrl).not.toHaveBeenCalled();
    });

    it('stays open and raises no further alert on a failed save', async () => {
      commitResolvesTo({ status: 'failed', message: 'boom' });

      await component.onCommitButton();

      expect(mockAlertService.danger).not.toHaveBeenCalled();
      expect(mockRouter.navigateByUrl).not.toHaveBeenCalled();
    });
  });


  /**
   * The editor is a routed page, so Close, the browser Back button and a nav-bar click are all the
   * same departure — all of them go through `unsavedChangesGuard`, which calls this. It is the
   * UI's only unsaved-changes protection.
   */
  describe('confirmLeaveWithUnsavedChanges', () => {
    function showReturns(answer: boolean | undefined): Subject<boolean> {
      const closeSubject = new Subject<boolean>();
      mockBsModalService.show.and.returnValue({ content: { closeSubject } } as any);
      if (answer !== undefined) {
        // Answer on the next tick, after the guard has subscribed.
        setTimeout(() => { closeSubject.next(answer); closeSubject.complete(); });
      }
      return closeSubject;
    }

    beforeEach(() => {
      component.mapping = buildMapping();
      component.stepperConfiguration = buildConfig();
      component.deploymentMapEntry = deploymentMapEntry;
      component.sourceTemplate = { a: 1 };
      component.targetTemplate = { b: 2 };
      component.stepperViewModel = { showExtensionSelectorsSource: false, showExtensionSelectorsTarget: false } as any;
      component.templateForm = new FormGroup({});
      component['initialDeploymentConnectors'] = JSON.stringify(deploymentMapEntry.connectors);
      component['initialContentSnapshot'] = captureMappingContentSnapshot(
        component.mapping, component.sourceTemplate, component.targetTemplate, component.mappingCode
      );
    });

    it('leaves immediately when nothing has changed', async () => {
      await expectAsync(component.confirmLeaveWithUnsavedChanges()).toBeResolvedTo(true);
      expect(mockBsModalService.show).not.toHaveBeenCalled();
    });

    it('leaves immediately in read-only mode', async () => {
      component.stepperConfiguration = buildConfig({ editorMode: EditorMode.READ_ONLY });
      component.mapping.name = 'edited somehow';

      await expectAsync(component.confirmLeaveWithUnsavedChanges()).toBeResolvedTo(true);
      expect(mockBsModalService.show).not.toHaveBeenCalled();
    });

    it('asks before discarding edited mapping content', async () => {
      component.mapping.name = 'edited';
      showReturns(true);

      await expectAsync(component.confirmLeaveWithUnsavedChanges()).toBeResolvedTo(true);
      expect(mockBsModalService.show).toHaveBeenCalled();
    });

    it('asks when only the connector selection changed', async () => {
      component.deploymentMapEntry = { identifier: '42', connectors: ['c1', 'c2'] };
      showReturns(true);

      await expectAsync(component.confirmLeaveWithUnsavedChanges()).toBeResolvedTo(true);
    });

    it('keeps the user in the editor when they decline', async () => {
      component.mapping.name = 'edited';
      showReturns(false);

      await expectAsync(component.confirmLeaveWithUnsavedChanges()).toBeResolvedTo(false);
    });

    it('keeps the user in the editor if the modal is destroyed without answering', async () => {
      // ConfirmationModalComponent.ngOnDestroy completes closeSubject without emitting; a bare
      // firstValueFrom would reject with EmptyError and break the navigation outright.
      component.mapping.name = 'edited';
      const closeSubject = showReturns(undefined);
      setTimeout(() => closeSubject.complete());

      await expectAsync(component.confirmLeaveWithUnsavedChanges()).toBeResolvedTo(false);
    });

    it('does not ask again after a successful save', async () => {
      component.mapping.name = 'edited';
      mockStepperService.commitMapping.and.resolveTo({
        status: 'saved', contentChanged: true, deploymentChanged: false, persisted: component.mapping
      });

      await component.onCommitButton();

      await expectAsync(component.confirmLeaveWithUnsavedChanges()).toBeResolvedTo(true);
      expect(mockBsModalService.show).not.toHaveBeenCalled();
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
