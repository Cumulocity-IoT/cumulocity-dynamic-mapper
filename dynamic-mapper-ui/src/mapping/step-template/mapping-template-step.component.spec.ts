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
import { AlertService } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
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
import { EditorMode } from '../shared/stepper.model';

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
  let isContentChangeValid$: Subject<boolean>;
  let contentValidEmissions: boolean[];

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
    editorMode: EditorMode.CREATE,
    direction: Direction.INBOUND,
    allowTemplateExpansion: false,
    ...overrides
  });

  beforeEach(async () => {
    isContentChangeValid$ = new Subject<boolean>();
    contentValidEmissions = [];
    isContentChangeValid$.subscribe((v) => contentValidEmissions.push(v));

    mockStepperService = jasmine.createSpyObj('MappingStepperService', ['evaluateFilterExpression'], {
      isContentChangeValid$: isContentChangeValid$
    });
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
    it('captures JSON content as the updated source template and flags the change valid', () => {
      const changes: ContentChanges = {
        previousContent: { json: { a: 1 } } as Content,
        updatedContent: { json: { a: 2 } } as Content
      };
      component.onSourceTemplateChanged(changes);

      expect(component.sourceTemplateUpdated).toEqual({ a: 2 });
      expect(contentValidEmissions).toContain(true);
    });

    it('parses valid text content into JSON', () => {
      const changes: ContentChanges = {
        previousContent: { json: { a: 1 } } as Content,
        updatedContent: { text: '{"a":3}' } as Content
      };
      component.onSourceTemplateChanged(changes);
      expect(component.sourceTemplateUpdated).toEqual({ a: 3 });
    });

    it('tolerates invalid JSON while the user is mid-edit (keeps raw content, stays valid)', () => {
      const changes: ContentChanges = {
        previousContent: { json: { a: 1 } } as Content,
        updatedContent: { text: '{ broken' } as Content
      };
      component.onSourceTemplateChanged(changes);

      expect(component.sourceTemplateUpdated).toEqual({ text: '{ broken' } as any);
      expect(contentValidEmissions[contentValidEmissions.length - 1]).toBe(true);
    });

    it('flags an invalid change and warns when a protected field is altered (with expansion on)', () => {
      component.stepperConfiguration = buildConfig({ allowTemplateExpansion: true });
      const changes: ContentChanges = {
        previousContent: { json: { _IDENTITY_: { externalId: 'x' }, v: 1 } } as Content,
        updatedContent: { json: { v: 1 } } as Content // _IDENTITY_ removed
      };

      component.onSourceTemplateChanged(changes);

      expect(contentValidEmissions[contentValidEmissions.length - 1]).toBe(false);
      expect(mockAlertService.add).toHaveBeenCalled();
    });

    it('flags an invalid change when a JSON array is used with a non-SmartFunction mapping', () => {
      component.mapping = buildMapping({ transformationType: TransformationType.DEFAULT });
      const changes: ContentChanges = {
        previousContent: { json: {} } as Content,
        updatedContent: { json: [1, 2, 3] } as Content
      };
      component.onSourceTemplateChanged(changes);
      expect(contentValidEmissions[contentValidEmissions.length - 1]).toBe(false);
    });
  });

  describe('onTargetTemplateChanged', () => {
    it('emits valid for a well-formed JSON object change', () => {
      const changes: ContentChanges = {
        previousContent: { json: {} } as Content,
        updatedContent: { json: { c8y_Temperature: { T: { value: 1 } } } } as Content
      };
      component.onTargetTemplateChanged(changes);
      expect(contentValidEmissions[contentValidEmissions.length - 1]).toBe(true);
    });

    it('returns early (valid) on un-parseable text', () => {
      const changes: ContentChanges = {
        previousContent: { json: {} } as Content,
        updatedContent: { text: 'not json' } as Content
      };
      component.onTargetTemplateChanged(changes);
      expect(contentValidEmissions[contentValidEmissions.length - 1]).toBe(true);
    });
  });

  describe('updateFilterExpressionResult', () => {
    it('stores the evaluated expression and writes the path back to the mapping', async () => {
      const evaluated = { result: 'true', resultType: 'boolean', valid: true };
      mockStepperService.evaluateFilterExpression.and.returnValue(Promise.resolve(evaluated));

      await component.updateFilterExpressionResult('$exists(foo)', { foo: 1 });

      expect(mockStepperService.evaluateFilterExpression).toHaveBeenCalledWith({ foo: 1 }, '$exists(foo)');
      expect(component.filterModel.filterExpression).toEqual(evaluated);
      expect(component.mapping.filterMapping).toBe('$exists(foo)');
      expect(component.filterFormly.get('filterMapping')?.value).toBe('$exists(foo)');
    });

    it('marks the expression invalid and sets a form error when evaluation throws', async () => {
      mockStepperService.evaluateFilterExpression.and.returnValue(Promise.reject(new Error('bad expr')));

      await component.updateFilterExpressionResult('$broken(');

      expect(component.filterModel.filterExpression?.valid).toBe(false);
      expect(component.filterFormly.get('filterMapping')?.errors).toBeTruthy();
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
      expect(mockStepperService.evaluateFilterExpression).toHaveBeenCalled();
    });
  });

  describe('onSampleTargetTemplatesButton', () => {
    it('emits a fresh sample target template for INBOUND mappings', () => {
      component.stepperConfiguration = buildConfig({ direction: Direction.INBOUND });
      component.mapping = buildMapping({ targetAPI: 'MEASUREMENT' });
      const emitted: any[] = [];
      component.targetTemplateChange.subscribe((t) => emitted.push(t));

      component.onSampleTargetTemplatesButton();

      expect(emitted.length).toBe(1);
      expect(emitted[0]).toBeDefined();
    });
  });
});
