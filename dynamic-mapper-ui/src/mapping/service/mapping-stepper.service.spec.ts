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

import { ChangeDetectorRef } from '@angular/core';
import { FormControl, FormGroup } from '@angular/forms';
import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { AlertService } from '@c8y/ngx-components';
import {
  CommitBlocker,
  CommitMappingRequest,
  CommitResult,
  commitSuccessMessage,
  MappingStepperService,
  snapshotConnectors
} from './mapping-stepper.service';
import { MappingService } from '../core/mapping.service';
import { ExtensionService } from '../../extension';
import { AIAgentService } from '../core/ai-agent.service';
import { EditorMode } from '../../shared/mapping/stepper.model';
import {
  Direction,
  Extension,
  ExtensionType,
  Mapping,
  MappingType,
  SharedService,
  StepperConfiguration,
  TransformationType
} from '../../shared';
import { CodeTemplate, CodeTemplateMap, ServiceConfiguration, TemplateType } from '../../configuration/shared/configuration.model';
import { captureMappingContentSnapshot } from '../../shared/mapping/util';
import { MappingValidationError } from '../../shared/mapping/mapping-validation-error';
import { DeploymentMapEntry } from '../../shared';

/**
 * Unit tests for the Phase 3 "stateless-but-mutating" editing operations moved from
 * MappingStepperComponent/MappingUnifiedEditorComponent into MappingStepperService — see
 * docs/planning/IMPLEMENTATION-PLAN-STEPPER-UNIFIED-EDITOR-DEDUP.md, Phase 3. These were
 * previously covered as component-level tests (asserting on `component.mapping`/
 * `component.mappingCode` etc. after calling the component's own method body); now that the
 * business logic itself lives here, it is tested directly against the real service instead of a
 * jasmine mock of it.
 */
describe('MappingStepperService', () => {
  let service: MappingStepperService;
  let mockSharedService: jasmine.SpyObj<SharedService>;
  let mockAlertService: jasmine.SpyObj<AlertService>;
  let mockAIAgentService: jasmine.SpyObj<AIAgentService>;
  let mockExtensionService: jasmine.SpyObj<ExtensionService>;
  let mockMappingService: jasmine.SpyObj<MappingService>;

  function makeMapping(overrides: Partial<Mapping> = {}): Mapping {
    return {
      id: 'test-id',
      identifier: 'test-identifier',
      name: 'Test',
      direction: Direction.INBOUND,
      targetAPI: 'MEASUREMENT',
      mappingType: MappingType.JSON,
      transformationType: TransformationType.DEFAULT,
      substitutions: [],
      sourceTemplate: '{}',
      targetTemplate: '{}',
      mappingTopic: 'test/topic',
      mappingTopicSample: 'test/topic',
      active: true,
      debug: false,
      tested: false,
      filterMapping: '',
      createNonExistingDevice: false,
      updateExistingDevice: false,
      useExternalId: false,
      externalIdType: '',
      qos: undefined,
      ...overrides
    } as Mapping;
  }

  function makeStepperConfig(overrides: Partial<StepperConfiguration> = {}): StepperConfiguration {
    return {
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
    } as StepperConfiguration;
  }

  beforeEach(() => {
    mockSharedService = jasmine.createSpyObj<SharedService>('SharedService', [
      'getCodeTemplates',
      'createCodeTemplate',
      'getFeatures',
      'getServiceConfiguration'
    ]);
    mockAlertService = jasmine.createSpyObj<AlertService>('AlertService', [
      'add',
      'remove',
      'success',
      'danger'
    ], { state: [] });
    mockAIAgentService = jasmine.createSpyObj<AIAgentService>('AIAgentService', ['getAIAgents']);
    mockExtensionService = jasmine.createSpyObj<ExtensionService>('ExtensionService', ['getProcessorExtensions']);
    mockMappingService = jasmine.createSpyObj<MappingService>('MappingService', [
      'evaluateExpression',
      'saveDraft',
      'createMapping',
      'updateDefinedDeploymentMapEntry',
      'refreshMappings'
    ]);
    mockMappingService.saveDraft.and.resolveTo(undefined as any);
    mockMappingService.createMapping.and.resolveTo(undefined as any);
    mockMappingService.updateDefinedDeploymentMapEntry.and.resolveTo(undefined as any);

    mockSharedService.getFeatures.and.resolveTo({ userHasMappingAdminRole: true, userHasMappingCreateRole: true } as any);
    mockSharedService.getServiceConfiguration.and.resolveTo({} as any);
    mockSharedService.getCodeTemplates.and.resolveTo({} as CodeTemplateMap);
    mockAIAgentService.getAIAgents.and.resolveTo([]);

    TestBed.configureTestingModule({
      providers: [
        MappingStepperService,
        { provide: MappingService, useValue: mockMappingService },
        { provide: SharedService, useValue: mockSharedService },
        { provide: ExtensionService, useValue: mockExtensionService },
        { provide: AIAgentService, useValue: mockAIAgentService },
        { provide: AlertService, useValue: mockAlertService }
      ]
    });

    service = TestBed.inject(MappingStepperService);
  });

  // -------------------------------------------------------------------------
  // raiseAlert
  // -------------------------------------------------------------------------

  describe('raiseAlert', () => {
    it('removes existing info/warning alerts before adding the new one', () => {
      const infoAlert = { type: 'info' };
      const warningAlert = { type: 'warning' };
      const dangerAlert = { type: 'danger' };
      Object.defineProperty(mockAlertService, 'state', { value: [infoAlert, warningAlert, dangerAlert] });

      service.raiseAlert({ type: 'success', text: 'done' } as any);

      expect(mockAlertService.remove).toHaveBeenCalledWith(infoAlert as any);
      expect(mockAlertService.remove).toHaveBeenCalledWith(warningAlert as any);
      expect(mockAlertService.remove).not.toHaveBeenCalledWith(dangerAlert as any);
      expect(mockAlertService.add).toHaveBeenCalledWith({ type: 'success', text: 'done' } as any);
    });
  });

  // -------------------------------------------------------------------------
  // patchExtensionFormValues
  // -------------------------------------------------------------------------

  describe('patchExtensionFormValues', () => {
    it('patches the form with the mapping extension fields on the next microtask', async () => {
      const templateForm = new FormGroup({
        extensionName: new FormControl(''),
        eventName: new FormControl(''),
        extensionParameter: new FormControl('')
      });
      const mapping = makeMapping({
        extension: { extensionName: 'ext1', eventName: 'evt1', parameter: { a: 1 } } as any
      });
      const cdr = jasmine.createSpyObj<ChangeDetectorRef>('ChangeDetectorRef', ['markForCheck']);

      service.patchExtensionFormValues(templateForm, mapping, cdr);
      await Promise.resolve(); // flush the queueMicrotask

      expect(templateForm.get('extensionName')?.value).toBe('ext1');
      expect(templateForm.get('eventName')?.value).toBe('evt1');
      expect(templateForm.get('extensionParameter')?.value).toContain('a: 1');
      expect(cdr.markForCheck).toHaveBeenCalled();
    });
  });

  // -------------------------------------------------------------------------
  // applyExtensionNameSelection
  // -------------------------------------------------------------------------

  describe('applyExtensionNameSelection', () => {
    it('creates the extension object and stores the name', () => {
      const mapping = makeMapping();
      delete mapping.extension;
      const extensions = new Map<string, Extension>();

      service.applyExtensionNameSelection('my-extension', mapping, extensions);

      expect(mapping.extension?.extensionName).toBe('my-extension');
    });
  });

  // -------------------------------------------------------------------------
  // updateSubstitutionValidity
  //
  // Phase 2 of the stepper/unified-editor deduplication. This drives
  // isSubstitutionValid$, which buildTemplateForm() mirrors into
  // templateForm.setErrors — so a wrong answer here silently blocks or unblocks
  // saving in both editors. The five OR-clauses are each covered below.
  // -------------------------------------------------------------------------

  describe('updateSubstitutionValidity', () => {
    /** A substitution that targets _IDENTITY_.externalId counts as a device identifier. */
    function withIdentifiers(count: number, direction = Direction.INBOUND): Mapping {
      const subs = Array.from({ length: count }, (_, i) => (
        direction === Direction.INBOUND
          ? { pathSource: `s${i}`, pathTarget: '_IDENTITY_.externalId' }
          : { pathSource: '_IDENTITY_.externalId', pathTarget: `t${i}` }
      ));
      return makeMapping({ direction, substitutions: subs as any });
    }

    function validityOf(mapping: Mapping, allowNoIdentifier = false, isBefore = false, showCode = false): boolean {
      let seen: boolean | undefined;
      const sub = service.isSubstitutionValid$.subscribe(v => (seen = v));
      service.updateSubstitutionValidity(mapping, allowNoIdentifier, isBefore, showCode);
      sub.unsubscribe();
      return seen!;
    }

    it('publishes the device-identifier count', () => {
      let count: number | undefined;
      const sub = service.countDeviceIdentifiers$.subscribe(n => (count = n));
      service.updateSubstitutionValidity(withIdentifiers(2), false, false, false);
      sub.unsubscribe();
      expect(count).toBe(2);
    });

    it('INBOUND is valid with exactly one identifier', () => {
      expect(validityOf(withIdentifiers(1))).toBe(true);
    });

    it('INBOUND is invalid with none, and with more than one', () => {
      expect(validityOf(withIdentifiers(0))).toBe(false);
      expect(validityOf(withIdentifiers(2))).toBe(false);
    });

    it('OUTBOUND is valid with one or more identifiers, invalid with none', () => {
      expect(validityOf(withIdentifiers(1, Direction.OUTBOUND))).toBe(true);
      expect(validityOf(withIdentifiers(3, Direction.OUTBOUND))).toBe(true);
      expect(validityOf(withIdentifiers(0, Direction.OUTBOUND))).toBe(false);
    });

    it('showCodeEditor short-circuits the identifier rules', () => {
      // Code-based transformations resolve the device themselves, so the substitution
      // count says nothing about validity.
      expect(validityOf(withIdentifiers(0), false, false, true)).toBe(true);
    });

    it('allowNoDefinedIdentifier short-circuits the identifier rules', () => {
      expect(validityOf(withIdentifiers(0), true, false, false)).toBe(true);
    });

    it('isBeforeSubstitutionStep short-circuits the identifier rules', () => {
      // Before the user has reached the substitution step there is nothing to judge yet.
      expect(validityOf(withIdentifiers(0), false, true, false)).toBe(true);
    });
  });

  // -------------------------------------------------------------------------
  // selectExtensionName
  //
  // Picks which of an extension's entries the event dropdown offers. The filter
  // is chosen from transformationType first, then falls back to the mapping's own
  // extensionType — getting it wrong offers the user events that cannot work.
  // -------------------------------------------------------------------------

  describe('selectExtensionName', () => {
    const inboundEntry = { eventName: 'in', extensionType: ExtensionType.EXTENSION_INBOUND } as any;
    const outboundEntry = { eventName: 'out', extensionType: ExtensionType.EXTENSION_OUTBOUND } as any;

    function extensionsWithBoth(): Map<string, Extension> {
      const extension = {
        extensionEntries: { in: inboundEntry, out: outboundEntry }
      } as unknown as Extension;
      return new Map([['ext', extension]]);
    }

    function eventsAfterSelecting(mapping: Mapping, extensions = extensionsWithBoth()): any[] {
      let seen: any[] = [];
      const sub = service.extensionEvents$.subscribe(e => (seen = e));
      service.selectExtensionName('ext', extensions, mapping);
      sub.unsubscribe();
      return seen;
    }

    it('emits an empty list when the extension is unknown', () => {
      expect(eventsAfterSelecting(makeMapping(), new Map())).toEqual([]);
    });

    it('EXTENSION_JAVA + INBOUND offers only inbound entries', () => {
      const mapping = makeMapping({
        transformationType: TransformationType.EXTENSION_JAVA, direction: Direction.INBOUND
      });
      expect(eventsAfterSelecting(mapping)).toEqual([inboundEntry]);
    });

    it('EXTENSION_JAVA + OUTBOUND offers only outbound entries', () => {
      const mapping = makeMapping({
        transformationType: TransformationType.EXTENSION_JAVA, direction: Direction.OUTBOUND
      });
      expect(eventsAfterSelecting(mapping)).toEqual([outboundEntry]);
    });

    it("falls back to the mapping's own extensionType for other transformation types", () => {
      const mapping = makeMapping({
        transformationType: TransformationType.DEFAULT,
        direction: Direction.INBOUND,
        extension: { extensionName: 'ext', extensionType: ExtensionType.EXTENSION_OUTBOUND } as any
      });
      // Direction is INBOUND, but the mapping pins an OUTBOUND extension type — the pin wins.
      expect(eventsAfterSelecting(mapping)).toEqual([outboundEntry]);
    });

    it('offers every entry when neither rule determines a type', () => {
      const mapping = makeMapping({ transformationType: TransformationType.DEFAULT });
      expect(eventsAfterSelecting(mapping)).toEqual([inboundEntry, outboundEntry]);
    });
  });

  // -------------------------------------------------------------------------
  // loadCodeTemplates
  // -------------------------------------------------------------------------

  describe('loadCodeTemplates', () => {
    it('base64-decodes each template body', async () => {
      mockSharedService.getCodeTemplates.and.resolveTo({
        t1: { name: 'One', templateType: TemplateType.INBOUND_SMART_FUNCTION, code: btoa('function onMessage() {}'),
              internal: false, readonly: false } as any
      });

      const result = await service.loadCodeTemplates();

      expect(result.get('t1')!.code).toBe('function onMessage() {}');
      expect(result.get('t1')!.id).toBe('t1');
    });

    it('keeps a template whose body is not decodable rather than failing the whole load', async () => {
      mockSharedService.getCodeTemplates.and.resolveTo({
        good: { name: 'Good', templateType: TemplateType.INBOUND_SMART_FUNCTION, code: btoa('ok'),
                internal: false, readonly: false } as any,
        bad: { name: 'Bad', templateType: TemplateType.INBOUND_SMART_FUNCTION, code: '!!!not-base64!!!',
               internal: false, readonly: false } as any
      });

      const result = await service.loadCodeTemplates();

      // One malformed template must not cost the user the rest of the list.
      expect(result.size).toBe(2);
      expect(result.get('good')!.code).toBe('ok');
      expect(result.has('bad')).toBe(true);
    });
  });

  // -------------------------------------------------------------------------
  // applyExtensionEventSelection
  // -------------------------------------------------------------------------

  describe('applyExtensionEventSelection', () => {
    it('copies the matching event entry and returns true when it has a parameter block', () => {
      const eventEntry = {
        eventName: 'evt',
        extensionType: ExtensionType.EXTENSION_INBOUND,
        direction: Direction.INBOUND,
        fqnClassName: 'com.acme.Ext',
        loaded: true,
        message: 'ok',
        parameter: { foo: 'bar' }
      };
      const extension = { extensionEntries: { evt: eventEntry } } as unknown as Extension;
      const extensions = new Map([['my-extension', extension]]);
      const mapping = makeMapping({ extension: { extensionName: 'my-extension' } as any });
      const templateForm = new FormGroup({ extensionParameter: new FormControl('') });

      const result = service.applyExtensionEventSelection('evt', mapping, extensions, templateForm);

      expect(result).toBe(true);
      expect(mapping.extension.eventName).toBe('evt');
      expect(mapping.extension.fqnClassName).toBe('com.acme.Ext');
      // Pre-fills the parameter when none was set yet
      expect(mapping.extension.parameter).toEqual({ foo: 'bar' });
    });

    it('returns false when the matched event has no parameter block', () => {
      const eventEntry = { eventName: 'evt', extensionType: ExtensionType.EXTENSION_INBOUND };
      const extension = { extensionEntries: { evt: eventEntry } } as unknown as Extension;
      const extensions = new Map([['my-extension', extension]]);
      const mapping = makeMapping({ extension: { extensionName: 'my-extension' } as any });
      const templateForm = new FormGroup({ extensionParameter: new FormControl('') });

      const result = service.applyExtensionEventSelection('evt', mapping, extensions, templateForm);

      expect(result).toBe(false);
    });

    it('returns undefined when no matching event entry is found', () => {
      const extension = { extensionEntries: {} } as unknown as Extension;
      const extensions = new Map([['my-extension', extension]]);
      const mapping = makeMapping({ extension: { extensionName: 'my-extension' } as any });
      const templateForm = new FormGroup({ extensionParameter: new FormControl('') });

      const result = service.applyExtensionEventSelection('missing-evt', mapping, extensions, templateForm);

      expect(result).toBeUndefined();
    });
  });

  // -------------------------------------------------------------------------
  // applyTargetAPIChange
  // -------------------------------------------------------------------------

  describe('applyTargetAPIChange', () => {
    it('refreshes templates and returns the target schema for INBOUND', () => {
      const mapping = makeMapping({ direction: Direction.INBOUND });
      const result = service.applyTargetAPIChange(mapping, Direction.INBOUND, 'EVENT');

      expect(mapping.targetTemplate).toBeDefined();
      expect(mapping.sourceTemplate).toBeDefined();
      expect(result.schemaTarget).toBeDefined();
      expect(result.schemaSource).toBeUndefined();
    });

    it('refreshes templates and returns the source schema for OUTBOUND', () => {
      const mapping = makeMapping({ direction: Direction.OUTBOUND });
      const result = service.applyTargetAPIChange(mapping, Direction.OUTBOUND, 'EVENT');

      expect(mapping.sourceTemplate).toBeDefined();
      expect(result.schemaSource).toBeDefined();
      expect(result.schemaTarget).toBeUndefined();
    });
  });

  // -------------------------------------------------------------------------
  // computeCodeFromTemplate
  // -------------------------------------------------------------------------

  describe('computeCodeFromTemplate', () => {
    const cfg = (supportESM: boolean) => ({ supportESM } as ServiceConfiguration);

    it('returns undefined when the selected template is unknown', () => {
      const result = service.computeCodeFromTemplate(new Map(), 'missing' as any, cfg(false), TransformationType.SMART_FUNCTION);
      expect(result).toBeUndefined();
    });

    it('returns the template code unchanged when ESM support is disabled', () => {
      const templates = new Map<string, CodeTemplate>([['t1', { code: 'function onMessage() {}' } as any]]);
      const result = service.computeCodeFromTemplate(templates, 't1' as any, cfg(false), TransformationType.SMART_FUNCTION);
      expect(result).toContain('function onMessage() {}');
    });

    it('appends an ESM export for Smart Functions when ESM support is enabled', () => {
      const templates = new Map<string, CodeTemplate>([['t1', { code: 'function onMessage() {}' } as any]]);
      const result = service.computeCodeFromTemplate(templates, 't1' as any, cfg(true), TransformationType.SMART_FUNCTION);
      expect(result).toContain('export { onMessage };');
    });

    it('does not duplicate an ESM export that is already present', () => {
      const templates = new Map<string, CodeTemplate>([
        ['t1', { code: 'function onMessage() {}\nexport { onMessage };' } as any]
      ]);
      const result = service.computeCodeFromTemplate(templates, 't1' as any, cfg(true), TransformationType.SMART_FUNCTION);
      expect(result!.match(/export \{ onMessage \}/g)?.length).toBe(1);
    });
  });

  // -------------------------------------------------------------------------
  // computeCodeTemplateEntries / computeExtensionItems
  // -------------------------------------------------------------------------

  describe('computeCodeTemplateEntries', () => {
    it('returns empty entries/items when no code templates are loaded', () => {
      const result = service.computeCodeTemplateEntries(undefined, Direction.INBOUND, TransformationType.DEFAULT);
      expect(result).toEqual({ entries: [], items: [] });
    });

    it('filters by direction/transformationType and derives display items', () => {
      const codeTemplates: CodeTemplateMap = {
        t1: { name: 'my template', templateType: 'INBOUND_DEFAULT' as unknown as TemplateType } as any,
        t2: { name: 'other template', templateType: 'OUTBOUND_DEFAULT' as unknown as TemplateType } as any
      };
      const result = service.computeCodeTemplateEntries(codeTemplates, Direction.INBOUND, TransformationType.DEFAULT);

      expect(result.entries.length).toBe(1);
      expect(result.entries[0].key).toBe('t1');
      expect(result.items).toEqual([{ label: 'My template (INBOUND_DEFAULT)', value: 't1' }]);
    });
  });

  describe('computeExtensionItems', () => {
    it('returns the extension names', () => {
      const extensions = new Map<string, Extension>([['ext1', {} as any], ['ext2', {} as any]]);
      expect(service.computeExtensionItems(extensions)).toEqual(['ext1', 'ext2']);
    });
  });

  // -------------------------------------------------------------------------
  // computeSampleTargetTemplate
  // -------------------------------------------------------------------------

  describe('computeSampleTargetTemplate', () => {
    it('returns an empty object for code/extension transformations (INBOUND)', () => {
      const mapping = makeMapping({ transformationType: TransformationType.SMART_FUNCTION });
      const stepperConfiguration = { direction: Direction.INBOUND } as StepperConfiguration;
      expect(service.computeSampleTargetTemplate(mapping, stepperConfiguration)).toEqual({});
    });

    it('returns the sample C8Y template for INBOUND, non-code transformations', () => {
      const mapping = makeMapping({ targetAPI: 'MEASUREMENT' as any });
      const stepperConfiguration = { direction: Direction.INBOUND, allowTemplateExpansion: false } as StepperConfiguration;
      const result = service.computeSampleTargetTemplate(mapping, stepperConfiguration);
      expect(result).toBeDefined();
    });
  });

  // -------------------------------------------------------------------------
  // createCodeTemplateAndRefresh
  // -------------------------------------------------------------------------

  // Code templates only exist for Smart Functions: toTemplateType() has entries for
  // INBOUND/OUTBOUND + SMART_FUNCTION only and throws otherwise. The UI reflects that —
  // "Create new code template" lives inside the code-editor section, which is rendered
  // only for code-based transformations. So DEFAULT is not a reachable input here.
  describe('createCodeTemplateAndRefresh', () => {
    it('creates the template, refetches the map, and shows a success alert on 2xx', async () => {
      mockSharedService.createCodeTemplate.and.resolveTo({ status: 201 } as any);
      const refreshedMap: CodeTemplateMap = { t1: {} as any };
      mockSharedService.getCodeTemplates.and.resolveTo(refreshedMap);

      const result = await service.createCodeTemplateAndRefresh(
        'My template', 'desc', 'code', Direction.INBOUND, TransformationType.SMART_FUNCTION
      );

      expect(result).toBe(refreshedMap);
      expect(mockAlertService.success).toHaveBeenCalled();
      expect(mockAlertService.danger).not.toHaveBeenCalled();
    });

    it('shows a danger alert when creation fails', async () => {
      mockSharedService.createCodeTemplate.and.resolveTo({ status: 500 } as any);
      mockSharedService.getCodeTemplates.and.resolveTo({} as CodeTemplateMap);

      await service.createCodeTemplateAndRefresh(
        'My template', 'desc', 'code', Direction.INBOUND, TransformationType.SMART_FUNCTION
      );

      expect(mockAlertService.danger).toHaveBeenCalled();
      expect(mockAlertService.success).not.toHaveBeenCalled();
    });
  });

  // -------------------------------------------------------------------------
  // initializeEditorSession
  // Phase 4 of docs/planning/IMPLEMENTATION-PLAN-STEPPER-UNIFIED-EDITOR-DEDUP.md — the
  // consolidated ngOnInit bootstrap previously duplicated across
  // MappingStepperComponent/MappingUnifiedEditorComponent.
  // -------------------------------------------------------------------------

  describe('initializeEditorSession', () => {
    function callbacks(overrides: Partial<{
      onSelectExtensionName: (n: string) => void;
      onSelectExtensionEvent: (e: string) => void;
      getSourceTemplate: () => any;
      setSourceTemplate: (t: any) => void;
    }> = {}) {
      return {
        onSelectExtensionName: jasmine.createSpy('onSelectExtensionName'),
        onSelectExtensionEvent: jasmine.createSpy('onSelectExtensionEvent'),
        getSourceTemplate: jasmine.createSpy('getSourceTemplate').and.returnValue(undefined),
        setSourceTemplate: jasmine.createSpy('setSourceTemplate'),
        ...overrides
      };
    }

    it('sets source/target systems from direction (INBOUND)', async () => {
      const mapping = makeMapping({ direction: Direction.INBOUND });
      const result = await service.initializeEditorSession(mapping, makeStepperConfig(), new Subject(), callbacks());
      expect(result.sourceSystem).toBe('Broker');
      expect(result.targetSystem).toBe('Cumulocity');
    });

    // Moved here from mapping-stepper.component.spec.ts: the subscription that mirrors
    // isSubstitutionValid$ into the template form lives in buildTemplateForm() since the
    // stepper/unified-editor deduplication, so the component spec (which mocks this
    // service) could never exercise it.
    it('mirrors the substitution validity stream into the template form errors', async () => {
      const mapping = makeMapping({ direction: Direction.INBOUND });
      const { templateForm } = await service.initializeEditorSession(
        mapping, makeStepperConfig(), new Subject(), callbacks()
      );

      service.isSubstitutionValid$.next(false);
      expect(templateForm.errors).toEqual({ incorrect: true });

      service.isSubstitutionValid$.next(true);
      expect(templateForm.errors).toBeNull();
    });

    it('sets source/target systems from direction (OUTBOUND)', async () => {
      const mapping = makeMapping({ direction: Direction.OUTBOUND });
      const result = await service.initializeEditorSession(mapping, makeStepperConfig({ direction: Direction.OUTBOUND }), new Subject(), callbacks());
      expect(result.sourceSystem).toBe('Cumulocity');
      expect(result.targetSystem).toBe('Broker');
    });

    it('resolves source/target JSON schemas', async () => {
      const mapping = makeMapping();
      const result = await service.initializeEditorSession(mapping, makeStepperConfig(), new Subject(), callbacks());
      expect(result.schemaSource).toBeDefined();
      expect(result.schemaTarget).toBeDefined();
    });

    it('sets code-editor help/label for Smart Function mappings', async () => {
      const mapping = makeMapping({ transformationType: TransformationType.SMART_FUNCTION });
      const result = await service.initializeEditorSession(mapping, makeStepperConfig(), new Subject(), callbacks());
      expect(result.codeEditorLabel).toContain('Smart functions');
    });

    it('sets code-editor help/label for deprecated SUBSTITUTION_AS_CODE mappings', async () => {
      // eslint-disable-next-line @typescript-eslint/no-deprecated
      const mapping = makeMapping({ transformationType: TransformationType.SUBSTITUTION_AS_CODE });
      const result = await service.initializeEditorSession(mapping, makeStepperConfig(), new Subject(), callbacks());
      expect(result.codeEditorLabel).toContain('creating substitutions');
      expect(result.codeEditorHelp).toContain('creating substitutions');
    });

    it('reports editorTemplatesReadOnly=false when the user has admin or create role', async () => {
      mockSharedService.getFeatures.and.resolveTo({ userHasMappingAdminRole: true, userHasMappingCreateRole: false } as any);
      const result = await service.initializeEditorSession(makeMapping(), makeStepperConfig(), new Subject(), callbacks());
      expect(result.editorTemplatesReadOnly).toBe(false);
    });

    it('reports editorTemplatesReadOnly=true when the user lacks both roles', async () => {
      mockSharedService.getFeatures.and.resolveTo({ userHasMappingAdminRole: false, userHasMappingCreateRole: false } as any);
      const result = await service.initializeEditorSession(makeMapping(), makeStepperConfig(), new Subject(), callbacks());
      expect(result.editorTemplatesReadOnly).toBe(true);
      expect(result.editorOptions.readOnly).toBe(false); // editorOptions.readOnly tracks EditorMode.READ_ONLY, not roles
    });

    it('builds a templateForm with the expected controls', async () => {
      const mapping = makeMapping({ extension: { extensionName: 'ext1', eventName: 'evt1', parameter: { a: 1 } } as any });
      const result = await service.initializeEditorSession(mapping, makeStepperConfig(), new Subject(), callbacks());
      expect(result.templateForm.get('extensionName')?.value).toBe('ext1');
      expect(result.templateForm.get('eventName')?.value).toBe('evt1');
      expect(result.templateForm.get('extensionParameter')?.value).toContain('a: 1');
    });

    it('disables extensionName/eventName in READ_ONLY mode', async () => {
      const result = await service.initializeEditorSession(
        makeMapping(), makeStepperConfig({ editorMode: EditorMode.READ_ONLY }), new Subject(), callbacks()
      );
      expect(result.templateForm.get('extensionName')?.disabled).toBe(true);
      expect(result.templateForm.get('eventName')?.disabled).toBe(true);
    });

    it('does not disable extensionName/eventName just for a hidden selector by default (unified editor behavior)', async () => {
      const result = await service.initializeEditorSession(makeMapping(), makeStepperConfig(), new Subject(), callbacks());
      expect(result.templateForm.get('extensionName')?.disabled).toBe(false);
    });

    it('disables extensionName/eventName while no selector is shown when disableExtensionSelectorsWhenHidden is true (stepper behavior)', async () => {
      const result = await service.initializeEditorSession(
        makeMapping(), makeStepperConfig(), new Subject(), callbacks(), true
      );
      expect(result.templateForm.get('extensionName')?.disabled).toBe(true);
      expect(result.templateForm.get('eventName')?.disabled).toBe(true);
    });

    it('extensionName control changes invoke the onSelectExtensionName callback', async () => {
      const destroy$ = new Subject<void>();
      const cbs = callbacks();
      const result = await service.initializeEditorSession(makeMapping(), makeStepperConfig(), destroy$, cbs);

      result.templateForm.get('extensionName')?.setValue('picked-extension');
      await new Promise(resolve => setTimeout(resolve, 150)); // flush debounceTime(100)

      expect(cbs.onSelectExtensionName).toHaveBeenCalledWith('picked-extension');
    });

    it('eventName control changes invoke the onSelectExtensionEvent callback', async () => {
      const destroy$ = new Subject<void>();
      const cbs = callbacks();
      const result = await service.initializeEditorSession(makeMapping(), makeStepperConfig(), destroy$, cbs);

      result.templateForm.get('eventName')?.setValue('picked-event');
      await new Promise(resolve => setTimeout(resolve, 150));

      expect(cbs.onSelectExtensionEvent).toHaveBeenCalledWith('picked-event');
    });

    it('stops invoking callbacks after destroy$ fires', async () => {
      const destroy$ = new Subject<void>();
      const cbs = callbacks();
      const result = await service.initializeEditorSession(makeMapping(), makeStepperConfig(), destroy$, cbs);

      destroy$.next();
      destroy$.complete();
      result.templateForm.get('extensionName')?.setValue('picked-extension');
      await new Promise(resolve => setTimeout(resolve, 150));

      expect(cbs.onSelectExtensionName).not.toHaveBeenCalled();
    });

    it('extensionParameter control changes update mapping.extension.parameter', async () => {
      const destroy$ = new Subject<void>();
      const mapping = makeMapping({ extension: { extensionName: 'ext1' } as any });
      const result = await service.initializeEditorSession(mapping, makeStepperConfig(), destroy$, callbacks());

      result.templateForm.get('extensionParameter')?.setValue('a: 2');
      await new Promise(resolve => setTimeout(resolve, 350)); // flush debounceTime(300)

      expect(mapping.extension.parameter).toEqual({ a: 2 });
    });

    it('re-expands the source template via the getter/setter callbacks when an OUTBOUND mapping property changes', async () => {
      const destroy$ = new Subject<void>();
      const cbs = callbacks({ getSourceTemplate: jasmine.createSpy().and.returnValue({ existing: true }) });
      await service.initializeEditorSession(makeMapping({ direction: Direction.OUTBOUND }), makeStepperConfig(), destroy$, cbs);

      service.notifyMappingPropertyChanged(makeMapping({ direction: Direction.OUTBOUND }));

      expect(cbs.setSourceTemplate).toHaveBeenCalled();
    });

    it('does not touch the source template when the mapping property change is INBOUND', async () => {
      const destroy$ = new Subject<void>();
      const cbs = callbacks({ getSourceTemplate: jasmine.createSpy().and.returnValue({ existing: true }) });
      await service.initializeEditorSession(makeMapping({ direction: Direction.INBOUND }), makeStepperConfig(), destroy$, cbs);

      service.notifyMappingPropertyChanged(makeMapping({ direction: Direction.INBOUND }));

      expect(cbs.setSourceTemplate).not.toHaveBeenCalled();
    });

    it('loads code templates and derives filtered entries/items', async () => {
      mockSharedService.getCodeTemplates.and.resolveTo({
        t1: { name: 'my template', templateType: 'INBOUND_DEFAULT' as unknown as TemplateType } as any
      } as CodeTemplateMap);
      const mapping = makeMapping({ direction: Direction.INBOUND, transformationType: TransformationType.DEFAULT });

      const result = await service.initializeEditorSession(mapping, makeStepperConfig(), new Subject(), callbacks());

      expect(result.codeTemplateEntries.length).toBe(1);
      expect(result.codeTemplateItems).toEqual([{ label: 'My template (INBOUND_DEFAULT)', value: 't1' }]);
    });

    it('checks AI-agent deployment using the loaded service configuration', async () => {
      mockSharedService.getServiceConfiguration.and.resolveTo({ jsonataAgent: 'agent-1' } as any);
      mockAIAgentService.getAIAgents.and.resolveTo([{ name: 'agent-1' } as any]);
      const mapping = makeMapping({ transformationType: TransformationType.JSONATA });

      const result = await service.initializeEditorSession(mapping, makeStepperConfig(), new Subject(), callbacks());

      expect(result.aiAgentDeployed).toBe(true);
      expect(result.aiAgent).toEqual({ name: 'agent-1' } as any);
    });

    it('builds filterFormlyFields required for OUTBOUND direction', async () => {
      const mapping = makeMapping({ direction: Direction.OUTBOUND });
      const result = await service.initializeEditorSession(mapping, makeStepperConfig({ direction: Direction.OUTBOUND }), new Subject(), callbacks());
      expect(result.filterFormlyFields[0].fieldGroup![0].templateOptions.required).toBe(true);
    });
  });

  // -------------------------------------------------------------------------
  // encodeMappingForCommit
  // Phase 5 of docs/planning/IMPLEMENTATION-PLAN-STEPPER-UNIFIED-EDITOR-DEDUP.md — the
  // consolidated onCommitButton() encoding block previously duplicated across
  // MappingStepperComponent/MappingUnifiedEditorComponent.
  // -------------------------------------------------------------------------

  describe('encodeMappingForCommit', () => {
    it('JSON-stringifies source/target templates when template expansion is disabled', () => {
      const mapping = makeMapping();
      const result = service.encodeMappingForCommit(
        mapping, { a: 1 }, { b: 2 }, undefined, undefined, false, EditorMode.CREATE
      );
      expect('error' in result).toBe(false);
      expect(mapping.sourceTemplate).toBe(JSON.stringify({ a: 1 }));
      expect(mapping.targetTemplate).toBe(JSON.stringify({ b: 2 }));
    });

    it('reduces (compacts) source/target templates when template expansion is allowed', () => {
      const mapping = makeMapping();
      // reduceSourceTemplate deletes the expansion tokens at the TOP level of the template,
      // so the fixture has to put them there — nested occurrences are left alone by design.
      service.encodeMappingForCommit(
        mapping, { a: 1, _TOPIC_LEVEL_: ['x'], _IDENTITY_: {} }, { b: 2 }, undefined, undefined, true, EditorMode.CREATE
      );
      expect(mapping.sourceTemplate).toBe(JSON.stringify({ a: 1 }));
      expect(mapping.targetTemplate).toBe(JSON.stringify({ b: 2 }));
    });

    it('encodes mappingCode to base64 (stripped of metadata tags) when provided', () => {
      const mapping = makeMapping();
      const result = service.encodeMappingForCommit(
        mapping, {}, {}, 'function onMessage() {}', undefined, false, EditorMode.CREATE
      );
      expect('error' in result).toBe(false);
      expect(mapping.code).toBeTruthy();
      expect(atob(mapping.code!)).toContain('function onMessage');
    });

    it('leaves mapping.code untouched when no mappingCode is provided', () => {
      const mapping = makeMapping({ code: undefined });
      service.encodeMappingForCommit(mapping, {}, {}, undefined, undefined, false, EditorMode.CREATE);
      expect(mapping.code).toBeUndefined();
    });

    it('always reports contentChanged=true for CREATE (no snapshot needed)', () => {
      const mapping = makeMapping();
      const result = service.encodeMappingForCommit(mapping, {}, {}, undefined, undefined, false, EditorMode.CREATE);
      expect('error' in result).toBe(false);
      expect((result as { contentChanged: boolean }).contentChanged).toBe(true);
    });

    it('detects no content change in UPDATE mode when nothing differs from the snapshot', () => {
      const mapping = makeMapping();
      const snapshot = captureMappingContentSnapshot(mapping, {}, {}, undefined);
      const result = service.encodeMappingForCommit(mapping, {}, {}, undefined, snapshot, false, EditorMode.UPDATE);
      expect('error' in result).toBe(false);
      expect((result as { contentChanged: boolean }).contentChanged).toBe(false);
    });

    it('detects a content change in UPDATE mode when the source template differs from the snapshot', () => {
      const mapping = makeMapping();
      const snapshot = captureMappingContentSnapshot(mapping, { original: true }, {}, undefined);
      const result = service.encodeMappingForCommit(mapping, { changed: true }, {}, undefined, snapshot, false, EditorMode.UPDATE);
      expect('error' in result).toBe(false);
      expect((result as { contentChanged: boolean }).contentChanged).toBe(true);
    });

    it('treats UPDATE mode with no snapshot as always changed (defensive fallback)', () => {
      const mapping = makeMapping();
      const result = service.encodeMappingForCommit(mapping, {}, {}, undefined, undefined, false, EditorMode.UPDATE);
      expect('error' in result).toBe(false);
      expect((result as { contentChanged: boolean }).contentChanged).toBe(true);
    });

    it('returns an error for a substitutions-as-code mapping with no code', () => {
      const mapping = makeMapping({ transformationType: TransformationType.SMART_FUNCTION });
      const result = service.encodeMappingForCommit(mapping, {}, {}, undefined, undefined, false, EditorMode.CREATE);
      expect('error' in result).toBe(true);
      expect((result as { error: string }).error).toContain('Internal error');
    });

    it('succeeds for a substitutions-as-code mapping that does have code', () => {
      const mapping = makeMapping({ transformationType: TransformationType.SMART_FUNCTION });
      const result = service.encodeMappingForCommit(mapping, {}, {}, 'function onMessage() {}', undefined, false, EditorMode.CREATE);
      expect('error' in result).toBe(false);
    });
  });

  /**
   * The CommitResult matrix for `commitMapping()` — the single commit path shared by the unified
   * editor and the stepper's parent (docs/planning/IMPLEMENTATION-PLAN-COMMIT-MAPPING.md).
   *
   * These exist so the call sites can be moved onto it safely: the save path touches drafts,
   * deployments and an optimistic-concurrency token, and has never had an executed regression run.
   */
  describe('commitMapping', () => {
    const CONNECTORS = ['connector-a'];

    function makeDeployment(connectors: string[] = CONNECTORS): DeploymentMapEntry {
      return { identifier: 'test-identifier', connectors } as DeploymentMapEntry;
    }

    /** An UPDATE commit of an unmodified mapping: no content change, no connector change. */
    function makeRequest(overrides: Partial<CommitMappingRequest> = {}): CommitMappingRequest {
      const mapping = overrides.mapping ?? makeMapping();
      const deploymentMapEntry = overrides.deploymentMapEntry ?? makeDeployment();
      return {
        mapping,
        deploymentMapEntry,
        initialDeploymentConnectors: snapshotConnectors(deploymentMapEntry),
        stepperConfiguration: makeStepperConfig(),
        sourceTemplate: {},
        targetTemplate: {},
        mappingCode: undefined,
        initialContentSnapshot: captureMappingContentSnapshot(mapping, {}, {}, undefined),
        ...overrides
      };
    }

    function expectSaved(result: CommitResult): Extract<CommitResult, { status: 'saved' }> {
      expect(result.status).toBe('saved');
      return result as Extract<CommitResult, { status: 'saved' }>;
    }

    describe('preconditions block before anything is written', () => {
      it('blocks on NO_CONNECTOR when the deployment selects none', async () => {
        const result = await service.commitMapping(makeRequest({ deploymentMapEntry: makeDeployment([]) }));

        expect(result).toEqual(jasmine.objectContaining({ status: 'blocked', blocker: CommitBlocker.NO_CONNECTOR }));
        expect(mockMappingService.saveDraft).not.toHaveBeenCalled();
        expect(mockMappingService.updateDefinedDeploymentMapEntry).not.toHaveBeenCalled();
      });

      it('blocks on MAPPING_TOPIC for INBOUND with a blank topic, and marks the control', async () => {
        const propertyFormly = new FormGroup({ mappingTopic: new FormControl('') });
        const result = await service.commitMapping(makeRequest({
          mapping: makeMapping({ mappingTopic: '   ' }),
          forms: { propertyFormly, templateForm: new FormGroup({}), validateExtensionSelection: false }
        }));

        expect(result).toEqual(jasmine.objectContaining({ status: 'blocked', blocker: CommitBlocker.MAPPING_TOPIC }));
        expect(propertyFormly.get('mappingTopic')?.errors).toEqual({ required: true });
        expect(propertyFormly.get('mappingTopic')?.touched).toBe(true);
        expect(mockMappingService.saveDraft).not.toHaveBeenCalled();
      });

      it('does not apply the topic check to OUTBOUND', async () => {
        const result = await service.commitMapping(makeRequest({
          mapping: makeMapping({ mappingTopic: '', direction: Direction.OUTBOUND }),
          stepperConfiguration: makeStepperConfig({ direction: Direction.OUTBOUND }),
          forms: { propertyFormly: new FormGroup({}), templateForm: new FormGroup({}), validateExtensionSelection: false }
        }));

        expect(result.status).toBe('saved');
      });

      it('blocks on PROPERTY_FORM when the general-settings form is invalid', async () => {
        const propertyFormly = new FormGroup({ other: new FormControl('', () => ({ bad: true })) });
        const result = await service.commitMapping(makeRequest({
          forms: { propertyFormly, templateForm: new FormGroup({}), validateExtensionSelection: false }
        }));

        expect(result).toEqual(jasmine.objectContaining({ status: 'blocked', blocker: CommitBlocker.PROPERTY_FORM }));
        expect(propertyFormly.get('other')?.touched).toBe(true);
      });

      it('blocks on EXTENSION_SELECTION only when the selectors are rendered', async () => {
        const templateForm = new FormGroup({
          extensionName: new FormControl('', () => ({ required: true })),
          eventName: new FormControl('')
        });

        const hidden = await service.commitMapping(makeRequest({
          forms: { propertyFormly: new FormGroup({}), templateForm, validateExtensionSelection: false }
        }));
        expect(hidden.status).toBe('saved');

        const shown = await service.commitMapping(makeRequest({
          forms: { propertyFormly: new FormGroup({}), templateForm, validateExtensionSelection: true }
        }));
        expect(shown).toEqual(jasmine.objectContaining({ status: 'blocked', blocker: CommitBlocker.EXTENSION_SELECTION }));
        expect(templateForm.get('extensionName')?.touched).toBe(true);
        expect(templateForm.get('eventName')?.touched).toBe(true);
      });

      it('skips every form-backed precondition when the caller passes no forms (the stepper)', async () => {
        // cdk-stepper already enforces linear completion, so an INBOUND mapping with no topic
        // cannot be reached there — and must not be blocked here either.
        const result = await service.commitMapping(makeRequest({ mapping: makeMapping({ mappingTopic: '' }) }));

        expect(result.status).toBe('saved');
      });

      it('blocks on ENCODING when the mapping is substitutions-as-code without code', async () => {
        const result = await service.commitMapping(makeRequest({
          mapping: makeMapping({ transformationType: TransformationType.SMART_FUNCTION }),
          mappingCode: undefined
        }));

        expect(result).toEqual(jasmine.objectContaining({ status: 'blocked', blocker: CommitBlocker.ENCODING }));
        expect(mockMappingService.saveDraft).not.toHaveBeenCalled();
      });
    });

    describe('save modes', () => {
      it('saves a draft on UPDATE when the content changed', async () => {
        const mapping = makeMapping();
        const result = await service.commitMapping(makeRequest({
          mapping,
          initialContentSnapshot: captureMappingContentSnapshot(mapping, { was: 'different' }, {}, undefined)
        }));

        expect(mockMappingService.saveDraft).toHaveBeenCalledWith(mapping.id, mapping);
        expect(mockMappingService.createMapping).not.toHaveBeenCalled();
        expect(expectSaved(result).contentChanged).toBe(true);
      });

      it('does not save a draft on UPDATE for a connector-only change', async () => {
        const deploymentMapEntry = makeDeployment(['connector-b']);
        const result = await service.commitMapping(makeRequest({
          deploymentMapEntry,
          initialDeploymentConnectors: snapshotConnectors(makeDeployment(['connector-a']))
        }));

        expect(mockMappingService.saveDraft).not.toHaveBeenCalled();
        expect(mockMappingService.updateDefinedDeploymentMapEntry).toHaveBeenCalledWith(deploymentMapEntry);
        const saved = expectSaved(result);
        expect(saved.contentChanged).toBe(false);
        expect(saved.deploymentChanged).toBe(true);
      });

      it('creates the mapping on CREATE, and always treats it as a content change', async () => {
        const mapping = makeMapping();
        const result = await service.commitMapping(makeRequest({
          mapping,
          stepperConfiguration: makeStepperConfig({ editorMode: EditorMode.CREATE })
        }));

        expect(mockMappingService.createMapping).toHaveBeenCalledWith(mapping);
        expect(mockMappingService.saveDraft).not.toHaveBeenCalled();
        expect(expectSaved(result).contentChanged).toBe(true);
      });

      it('creates the mapping on COPY', async () => {
        await service.commitMapping(makeRequest({ stepperConfiguration: makeStepperConfig({ editorMode: EditorMode.COPY }) }));

        expect(mockMappingService.createMapping).toHaveBeenCalled();
      });

      it('returns the server copy as `persisted`, so a caller that stays open can re-baseline', async () => {
        const mapping = makeMapping({ lastUpdate: 1000 } as any);
        const serverCopy = makeMapping({ lastUpdate: 2000 } as any);
        mockMappingService.saveDraft.and.resolveTo(serverCopy);

        const result = await service.commitMapping(makeRequest({
          mapping,
          initialContentSnapshot: captureMappingContentSnapshot(mapping, { was: 'different' }, {}, undefined)
        }));

        expect(expectSaved(result).persisted).toBe(serverCopy);
      });

      it('falls back to the request mapping when nothing was written', async () => {
        const mapping = makeMapping();

        const result = await service.commitMapping(makeRequest({ mapping }));

        // Connector-only/no-op update: no save call, so there is no server copy to adopt.
        expect(mockMappingService.saveDraft).not.toHaveBeenCalled();
        expect(expectSaved(result).persisted).toBe(mapping);
      });

      it('refreshes the grid for the mapping direction', async () => {
        await service.commitMapping(makeRequest({ stepperConfiguration: makeStepperConfig({ direction: Direction.OUTBOUND }) }));

        expect(mockMappingService.refreshMappings).toHaveBeenCalledWith(Direction.OUTBOUND);
      });
    });

    describe('deployment persistence', () => {
      it('writes the deployment when the connectors changed', async () => {
        const deploymentMapEntry = makeDeployment(['connector-b']);
        await service.commitMapping(makeRequest({
          deploymentMapEntry,
          initialDeploymentConnectors: snapshotConnectors(makeDeployment(['connector-a']))
        }));

        expect(mockMappingService.updateDefinedDeploymentMapEntry).toHaveBeenCalledWith(deploymentMapEntry);
      });

      it('writes the deployment on CREATE even though the connectors are unchanged', async () => {
        await service.commitMapping(makeRequest({ stepperConfiguration: makeStepperConfig({ editorMode: EditorMode.CREATE }) }));

        expect(mockMappingService.updateDefinedDeploymentMapEntry).toHaveBeenCalled();
      });

      it('skips the redundant write on UPDATE when the connectors are unchanged', async () => {
        // The backend PUT reconciles subscriptions across all connectors live, so a no-op write
        // is not free. This is the reconciliation recorded in §4 of the plan: the stepper parent
        // used to write unconditionally here.
        await service.commitMapping(makeRequest());

        expect(mockMappingService.updateDefinedDeploymentMapEntry).not.toHaveBeenCalled();
      });

      it('does not write the deployment when the mapping itself failed to save', async () => {
        mockMappingService.createMapping.and.rejectWith(new Error('boom'));

        const result = await service.commitMapping(makeRequest({
          stepperConfiguration: makeStepperConfig({ editorMode: EditorMode.CREATE })
        }));

        expect(result.status).toBe('failed');
        expect(mockMappingService.updateDefinedDeploymentMapEntry).not.toHaveBeenCalled();
      });

      it('still reports saved when only the deployment write fails, but alerts the user', async () => {
        mockMappingService.updateDefinedDeploymentMapEntry.and.rejectWith(new Error('no such connector'));

        const result = await service.commitMapping(makeRequest({
          deploymentMapEntry: makeDeployment(['connector-b']),
          initialDeploymentConnectors: snapshotConnectors(makeDeployment(['connector-a']))
        }));

        expect(result.status).toBe('saved');
        expect(mockAlertService.danger).toHaveBeenCalledWith(jasmine.stringContaining('no such connector'));
      });
    });

    describe('failures', () => {
      it('reports rejected, without an alert, when the server rejects the draft', async () => {
        const error = new MappingValidationError('invalid', ['e1'], []);
        mockMappingService.saveDraft.and.rejectWith(error);
        const mapping = makeMapping();

        const result = await service.commitMapping(makeRequest({
          mapping,
          initialContentSnapshot: captureMappingContentSnapshot(mapping, { was: 'different' }, {}, undefined)
        }));

        expect(result).toEqual({ status: 'rejected', error });
        // The caller lists the problems in a drawer instead; a toast can only be dismissed.
        expect(mockAlertService.danger).not.toHaveBeenCalled();
        expect(mockMappingService.updateDefinedDeploymentMapEntry).not.toHaveBeenCalled();
      });

      it('reports rejected when the server rejects a create', async () => {
        const error = new MappingValidationError('invalid', ['e1'], []);
        mockMappingService.createMapping.and.rejectWith(error);

        const result = await service.commitMapping(makeRequest({
          stepperConfiguration: makeStepperConfig({ editorMode: EditorMode.CREATE })
        }));

        expect(result).toEqual({ status: 'rejected', error });
      });

      it('reports failed and alerts once for any other save error', async () => {
        mockMappingService.saveDraft.and.rejectWith(new Error('network down'));
        const mapping = makeMapping();

        const result = await service.commitMapping(makeRequest({
          mapping,
          initialContentSnapshot: captureMappingContentSnapshot(mapping, { was: 'different' }, {}, undefined)
        }));

        expect(result.status).toBe('failed');
        expect(mockAlertService.danger).toHaveBeenCalledTimes(1);
        expect(mockAlertService.danger).toHaveBeenCalledWith(jasmine.stringContaining('network down'));
      });

      it('still refreshes the grid after a failed save', async () => {
        mockMappingService.createMapping.and.rejectWith(new Error('boom'));

        await service.commitMapping(makeRequest({ stepperConfiguration: makeStepperConfig({ editorMode: EditorMode.CREATE }) }));

        expect(mockMappingService.refreshMappings).toHaveBeenCalled();
      });
    });

    /**
     * `lastUpdate` is the optimistic-concurrency token: MappingVersionService.saveDraft rejects a
     * draft whose lastUpdate differs from the stored snapshot's. The client must echo back what it
     * received — stamping Date.now() here would make every concurrent-edit check pass vacuously.
     */
    it('never stamps lastUpdate', async () => {
      const mapping = makeMapping({ lastUpdate: 1234567890 } as any);
      await service.commitMapping(makeRequest({
        mapping,
        initialContentSnapshot: captureMappingContentSnapshot(mapping, { was: 'different' }, {}, undefined)
      }));

      expect(mockMappingService.saveDraft).toHaveBeenCalled();
      expect(mockMappingService.saveDraft.calls.mostRecent().args[1].lastUpdate).toBe(1234567890);
    });
  });

  describe('commitSuccessMessage', () => {
    const saved = (contentChanged: boolean, deploymentChanged: boolean) =>
      ({ status: 'saved', contentChanged, deploymentChanged }) as Extract<CommitResult, { status: 'saved' }>;

    it('announces a creation for every non-UPDATE mode', () => {
      expect(commitSuccessMessage(saved(true, true), 'M', EditorMode.CREATE)).toContain('created successfully');
      expect(commitSuccessMessage(saved(true, true), 'M', EditorMode.COPY)).toContain('created successfully');
    });

    it('distinguishes the three UPDATE outcomes', () => {
      expect(commitSuccessMessage(saved(true, true), 'M', EditorMode.UPDATE))
        .toContain('Saved draft and connector assignments');
      expect(commitSuccessMessage(saved(true, false), 'M', EditorMode.UPDATE))
        .toContain('Saved draft for M');
      expect(commitSuccessMessage(saved(false, true), 'M', EditorMode.UPDATE))
        .toContain('Connector assignments for M saved');
    });

    it('says nothing when an UPDATE changed nothing', () => {
      expect(commitSuccessMessage(saved(false, false), 'M', EditorMode.UPDATE)).toBeUndefined();
    });
  });

});
