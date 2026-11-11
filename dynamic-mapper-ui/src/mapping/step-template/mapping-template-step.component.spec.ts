/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
import { AlertService } from '@c8y/ngx-components';
import { Content } from 'vanilla-jsoneditor';
import { MappingTemplateStepComponent } from './mapping-template-step.component';
import { MappingStepperService } from '../service/mapping-stepper.service';
import {
  Direction,
  Mapping,
  StepperConfiguration,
  TransformationType,
  MappingType,
  Qos,
  ContentChanges
} from '../../shared';

/**
 * Unit tests for {@link MappingTemplateStepComponent}.
 *
 * This child component owns the template-editing logic that the stepper used to reach into
 * via `@ViewChild`. Testing it directly is both more robust and more focused: the validation
 * branches (protected-field changes, transformation-type checks) and the filter-expression
 * evaluation are the parts most likely to regress.
 */
describe('MappingTemplateStepComponent', () => {
  let component: MappingTemplateStepComponent;
  let fixture: ComponentFixture<MappingTemplateStepComponent>;
  let mockStepperService: jasmine.SpyObj<MappingStepperService>;
  let mockAlertService: jasmine.SpyObj<AlertService>;

  const buildMapping = (overrides: Partial<Mapping> = {}): Mapping => ({
    id: '1',
    identifier: 'm',
    name: 'm',
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
    persistence: 'create',
    direction: Direction.INBOUND,
    allowTemplateExpansion: false,
    ...overrides
  });

  beforeEach(async () => {
    mockStepperService = jasmine.createSpyObj('MappingStepperService', ['evaluateFilterExpression', 'computeSampleTargetTemplate']);
    mockAlertService = jasmine.createSpyObj('AlertService', ['add', 'remove'], { state: [] });

    TestBed.overrideComponent(MappingTemplateStepComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });

    await TestBed.configureTestingModule({
      imports: [MappingTemplateStepComponent],
      providers: [
        { provide: MappingStepperService, useValue: mockStepperService },
        { provide: AlertService, useValue: mockAlertService }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(MappingTemplateStepComponent);
    component = fixture.componentInstance;
    component.mapping = buildMapping();
    component.stepperConfiguration = buildConfig();
    component.sourceTemplate = {};
    component.targetTemplate = {};
    component.filterFormly = new FormGroup({ filterMapping: new FormControl('') });
  });

  describe('onSourceTemplateChanged', () => {
    it('flags an ordinary JSON change valid and reports no issues', () => {
      const changes: ContentChanges = {
        previousContent: { json: { a: 1 } } as Content,
        updatedContent: { json: { a: 2 } } as Content
      };
      component.onSourceTemplateChanged(changes);

      expect(component.sourceIssues).toEqual({ protectedFieldsChanged: false, arrayNeedsSmartFunction: false });
      expect(component.contentChangeValid).toBeTrue();
    });

    it('parses valid text content into JSON before judging it', () => {
      component.mapping = buildMapping({ transformationType: TransformationType.DEFAULT });
      component.onSourceTemplateChanged({
        previousContent: { json: {} } as Content,
        updatedContent: { text: '[1,2]' } as Content
      });
      expect(component.sourceIssues.arrayNeedsSmartFunction).toBeTrue();
    });

    it('tolerates invalid JSON while the user is mid-edit (stays valid, no issues)', () => {
      const changes: ContentChanges = {
        previousContent: { json: { a: 1 } } as Content,
        updatedContent: { text: '{ broken' } as Content
      };
      component.onSourceTemplateChanged(changes);

      expect(component.sourceIssues).toEqual({ protectedFieldsChanged: false, arrayNeedsSmartFunction: false });
      expect(component.contentChangeValid).toBe(true);
    });

    it('flags an invalid change and warns when a protected field is altered (with expansion on)', () => {
      component.stepperConfiguration = buildConfig({ allowTemplateExpansion: true });
      // The protected-field check is deliberately skipped while metadata is hidden from the
      // editor (their absence would otherwise look like a deletion), so this test has to make
      // the metadata visible to exercise it.
      component.showSourceMetadata = true;
      const changes: ContentChanges = {
        previousContent: { json: { _IDENTITY_: { externalId: 'x' }, v: 1 } } as Content,
        updatedContent: { json: { v: 1 } } as Content // _IDENTITY_ removed
      };

      component.onSourceTemplateChanged(changes);

      expect(component.contentChangeValid).toBe(false);
      expect(component.sourceIssues.protectedFieldsChanged).toBeTrue();
      expect(mockAlertService.add).toHaveBeenCalled();
    });

    it('flags an invalid change when a JSON array is used with a non-SmartFunction mapping', () => {
      component.mapping = buildMapping({ transformationType: TransformationType.DEFAULT });
      const changes: ContentChanges = {
        previousContent: { json: {} } as Content,
        updatedContent: { json: [1, 2, 3] } as Content
      };
      component.onSourceTemplateChanged(changes);
      expect(component.contentChangeValid).toBe(false);
      expect(component.sourceIssues.arrayNeedsSmartFunction).toBeTrue();
    });

    it('does not record target edits as source issues', () => {
      component.mapping = buildMapping({ transformationType: TransformationType.DEFAULT });
      component.onTargetTemplateChanged({ previousContent: { json: {} } as Content, updatedContent: { json: [1] } as Content });

      expect(component.sourceIssues.arrayNeedsSmartFunction).toBeFalse();
    });
  });

  describe('onTargetTemplateChanged', () => {
    it('emits valid for a well-formed JSON object change', () => {
      const changes: ContentChanges = {
        previousContent: { json: {} } as Content,
        updatedContent: { json: { c8y_Temperature: { T: { value: 1 } } } } as Content
      };
      component.onTargetTemplateChanged(changes);
      expect(component.contentChangeValid).toBe(true);
    });

    it('returns early (valid) on un-parseable text', () => {
      const changes: ContentChanges = {
        previousContent: { json: {} } as Content,
        updatedContent: { text: 'not json' } as Content
      };
      component.onTargetTemplateChanged(changes);
      expect(component.contentChangeValid).toBe(true);
    });
  });

  describe('updateFilterExpressionResult', () => {
    // The step is OnPush. The result arrives after an await, and from the debounced filter input and the
    // host's direct calls, so no event of this view marks it; it has to mark itself.
    it('marks itself for checking when an evaluation succeeds', async () => {
      const markForCheck = spyOn(component['cdr'], 'markForCheck');
      mockStepperService.evaluateFilterExpression.and.returnValue(
        Promise.resolve({ result: 'true', resultType: 'boolean', valid: true } as any)
      );

      await component.updateFilterExpressionResult('$exists(foo)', { foo: 1 });

      expect(markForCheck).toHaveBeenCalled();
    });

    it('marks itself for checking when an evaluation fails', async () => {
      const markForCheck = spyOn(component['cdr'], 'markForCheck');
      mockStepperService.evaluateFilterExpression.and.returnValue(Promise.reject(new Error('bad expr')));

      await component.updateFilterExpressionResult('$broken(');

      expect(markForCheck).toHaveBeenCalled();
    });

    it('stores the evaluated expression and announces the path to the host', async () => {
      const evaluated = { result: 'true', resultType: 'boolean', valid: true };
      mockStepperService.evaluateFilterExpression.and.returnValue(Promise.resolve(evaluated));
      const announced: string[] = [];
      component.filterMappingChange.subscribe(p => announced.push(p));

      await component.updateFilterExpressionResult('$exists(foo)', { foo: 1 });

      expect(mockStepperService.evaluateFilterExpression as unknown as jasmine.Spy).toHaveBeenCalledWith({ foo: 1 }, '$exists(foo)');
      expect(component.filterModel.filterExpression).toEqual(evaluated);
      expect(announced).toEqual(['$exists(foo)']);
      expect(component.mapping.filterMapping).toBe(''); // the step never writes the mapping
      expect(component.filterFormly.get('filterMapping')?.value).toBe('$exists(foo)');
    });

    it('marks the expression invalid and sets a form error when evaluation throws', async () => {
      mockStepperService.evaluateFilterExpression.and.returnValue(Promise.reject(new Error('bad expr')));

      await component.updateFilterExpressionResult('$broken(');

      expect(component.filterModel.filterExpression?.valid).toBe(false);
      expect(component.filterFormly.get('filterMapping')?.errors).toBeTruthy();
    });

    it('surfaces the evaluated value and a form error when the result is not boolean', async () => {
      const evaluated = {
        result: '"6aa7d0c0211301ebaeef09f9"',
        resultType: 'String',
        valid: false,
        message: 'The filter expression must evaluate to a boolean value: either true or false'
      };
      mockStepperService.evaluateFilterExpression.and.returnValue(Promise.resolve(evaluated));

      await component.updateFilterExpressionResult('id', { id: '6aa7d0c0211301ebaeef09f9' });

      expect(component.filterModel.filterExpression).toEqual(evaluated);
      expect(component.filterFormly.get('filterMapping')?.errors).toEqual({
        validationError: { message: evaluated.message }
      });
    });

    it('clears prior info/warning alerts before evaluating', async () => {
      const warn = { type: 'warning', text: 'old' } as any;
      Object.defineProperty(mockAlertService, 'state', { get: () => [warn], configurable: true });
      mockStepperService.evaluateFilterExpression.and.returnValue(
        Promise.resolve({ result: '', resultType: 'empty', valid: true })
      );

      await component.updateFilterExpressionResult('$exists(foo)', {});

      expect(mockAlertService.remove).toHaveBeenCalledWith(warn);
    });
  });

  describe('onOverwriteFilterMapping', () => {
    it('promotes the selected path into the filter model and evaluates it', async () => {
      mockStepperService.evaluateFilterExpression.and.returnValue(
        Promise.resolve({ result: 'true', resultType: 'boolean', valid: true })
      );
      component.onSelectedPathFilterMappingChanged('$.deep.path');

      component.onOverwriteFilterMapping();

      expect(component.filterModel.filterMapping).toBe('$.deep.path');
      expect(mockStepperService.evaluateFilterExpression as unknown as jasmine.Spy).toHaveBeenCalled();
    });
  });

  describe('onSampleTargetTemplatesButton', () => {
    it('emits the sample target template the service computes', () => {
      mockStepperService.computeSampleTargetTemplate.and.returnValue({ sample: true });
      const emitted: any[] = [];
      component.targetTemplateChange.subscribe((t) => emitted.push(t));

      component.onSampleTargetTemplatesButton();

      expect(mockStepperService.computeSampleTargetTemplate as unknown as jasmine.Spy).toHaveBeenCalledWith(component.mapping, component.stepperConfiguration);
      expect(emitted).toEqual([{ sample: true }]);
    });
  });

  describe('getSourceTemplate / getTargetTemplate', () => {
    const full = { value: 1, _IDENTITY_: { externalId: 'x' }, _TOPIC_LEVEL_: ['a'] };
    const editor = (content: any) => ({ get: () => content }) as any;

    it('restores hidden metadata keys onto what the editor holds', () => {
      component.sourceTemplate = full;
      component.showSourceMetadata = false;
      Object.assign(component, { editorSourceStepTemplate: () => editor({ value: 2 }) }); // displayed template, metadata stripped

      expect(component.getSourceTemplate() as unknown).toEqual({ value: 2, _IDENTITY_: { externalId: 'x' }, _TOPIC_LEVEL_: ['a'] });
    });

    it('returns the editor content as is while metadata is shown', () => {
      component.targetTemplate = full;
      component.showTargetMetadata = true;
      Object.assign(component, { editorTargetStepTemplate: () => editor({ value: 2 }) }); // user removed the metadata on purpose

      expect(component.getTargetTemplate() as unknown).toEqual({ value: 2 });
    });

    it('does not resurrect a key the user edited into a different value', () => {
      component.sourceTemplate = full;
      component.showSourceMetadata = false;
      Object.assign(component, { editorSourceStepTemplate: () => editor({ value: 2, _IDENTITY_: { externalId: 'edited' } }) });

      expect((component.getSourceTemplate() as Record<string, unknown>)['_IDENTITY_']).toEqual({ externalId: 'edited' });
    });

    it('is undefined without an editor, so the host keeps its own value', () => {
      expect(component.getSourceTemplate() as unknown).toBeUndefined();
      expect(component.getTargetTemplate() as unknown).toBeUndefined();
    });
  });
});
