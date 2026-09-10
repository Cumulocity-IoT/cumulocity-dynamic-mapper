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
import { MappingStepperService } from './mapping-stepper.service';
import { MappingService } from '../core/mapping.service';
import { ExtensionService } from '../../extension';
import { AIAgentService } from '../core/ai-agent.service';
import { EditorMode } from '../shared/stepper.model';
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
import { captureMappingContentSnapshot } from '../shared/util';

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

    mockSharedService.getFeatures.and.resolveTo({ userHasMappingAdminRole: true, userHasMappingCreateRole: true } as any);
    mockSharedService.getServiceConfiguration.and.resolveTo({} as any);
    mockSharedService.getCodeTemplates.and.resolveTo({} as CodeTemplateMap);
    mockAIAgentService.getAIAgents.and.resolveTo([]);

    TestBed.configureTestingModule({
      providers: [
        MappingStepperService,
        { provide: MappingService, useValue: jasmine.createSpyObj('MappingService', ['evaluateExpression']) },
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

  describe('createCodeTemplateAndRefresh', () => {
    it('creates the template, refetches the map, and shows a success alert on 2xx', async () => {
      mockSharedService.createCodeTemplate.and.resolveTo({ status: 201 } as any);
      const refreshedMap: CodeTemplateMap = { t1: {} as any };
      mockSharedService.getCodeTemplates.and.resolveTo(refreshedMap);

      const result = await service.createCodeTemplateAndRefresh(
        'My template', 'desc', 'code', Direction.INBOUND, TransformationType.DEFAULT
      );

      expect(result).toBe(refreshedMap);
      expect(mockAlertService.success).toHaveBeenCalled();
      expect(mockAlertService.danger).not.toHaveBeenCalled();
    });

    it('shows a danger alert when creation fails', async () => {
      mockSharedService.createCodeTemplate.and.resolveTo({ status: 500 } as any);
      mockSharedService.getCodeTemplates.and.resolveTo({} as CodeTemplateMap);

      await service.createCodeTemplateAndRefresh(
        'My template', 'desc', 'code', Direction.INBOUND, TransformationType.DEFAULT
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
      service.encodeMappingForCommit(
        mapping, { a: { _TOPIC_LEVEL_: '1' } }, { b: 2 }, undefined, undefined, true, EditorMode.CREATE
      );
      // reduceSourceTemplate strips expansion metadata — result differs from a plain JSON.stringify
      expect(mapping.sourceTemplate).not.toBe(JSON.stringify({ a: { _TOPIC_LEVEL_: '1' } }));
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
});
