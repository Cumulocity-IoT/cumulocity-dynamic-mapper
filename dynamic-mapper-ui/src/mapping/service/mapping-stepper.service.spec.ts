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
import { AlertService } from '@c8y/ngx-components';
import { MappingStepperService } from './mapping-stepper.service';
import { MappingService } from '../core/mapping.service';
import { ExtensionService } from '../../extension';
import { AIAgentService } from '../core/ai-agent.service';
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

  beforeEach(() => {
    mockSharedService = jasmine.createSpyObj<SharedService>('SharedService', [
      'getCodeTemplates',
      'createCodeTemplate'
    ]);
    mockAlertService = jasmine.createSpyObj<AlertService>('AlertService', [
      'add',
      'remove',
      'success',
      'danger'
    ], { state: [] });

    TestBed.configureTestingModule({
      providers: [
        MappingStepperService,
        { provide: MappingService, useValue: jasmine.createSpyObj('MappingService', ['evaluateExpression']) },
        { provide: SharedService, useValue: mockSharedService },
        { provide: ExtensionService, useValue: jasmine.createSpyObj('ExtensionService', ['getProcessorExtensions']) },
        { provide: AIAgentService, useValue: jasmine.createSpyObj('AIAgentService', ['getAIAgents']) },
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
});
