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

import { TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { BottomDrawerRef, BottomDrawerService } from '@c8y/ngx-components';
import { MappingTypeDrawerComponent } from './mapping-type-drawer.component';
import { SharedService, Direction, MappingType, TransformationType } from '../../shared';
import { ExtensionService } from '../../extension';
import { AIAgentService } from '../core/ai-agent.service';
import { ServiceConfiguration } from '../../configuration';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeServiceConfiguration(): ServiceConfiguration {
  return { supportESM: false } as ServiceConfiguration;
}

/** Waits for the microtask queue (chained promise .thens included) to fully drain. */
function flushPromises(): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, 0));
}

async function createComponent(direction: Direction): Promise<MappingTypeDrawerComponent> {
  const fixture = TestBed.createComponent(MappingTypeDrawerComponent);
  const component = fixture.componentInstance;
  component.direction = direction;
  await component.ngOnInit();
  return component;
}

// ---------------------------------------------------------------------------
// MappingTypeDrawerComponent
// ---------------------------------------------------------------------------

describe('MappingTypeDrawerComponent', () => {
  let mockSharedService: jasmine.SpyObj<SharedService>;
  let mockExtensionService: jasmine.SpyObj<ExtensionService>;
  let mockAIAgentService: jasmine.SpyObj<AIAgentService>;
  let mockDrawerRef: jasmine.SpyObj<BottomDrawerRef<any>>;

  beforeEach(() => {
    mockSharedService = jasmine.createSpyObj<SharedService>('SharedService', [
      'getServiceConfiguration',
      'getCodeTemplatesByType'
    ]);
    mockSharedService.getServiceConfiguration.and.resolveTo(makeServiceConfiguration());
    mockSharedService.getCodeTemplatesByType.and.resolveTo([]);

    mockExtensionService = jasmine.createSpyObj<ExtensionService>('ExtensionService', ['getProcessorExtensions']);
    mockExtensionService.getProcessorExtensions.and.resolveTo(new Map());

    mockAIAgentService = jasmine.createSpyObj<AIAgentService>('AIAgentService', ['getAIAgents']);
    mockAIAgentService.getAIAgents.and.resolveTo([]);

    mockDrawerRef = jasmine.createSpyObj<BottomDrawerRef<any>>('BottomDrawerRef', ['close']);
    mockDrawerRef.close.and.resolveTo();

    TestBed.configureTestingModule({
      imports: [MappingTypeDrawerComponent],
      providers: [
        { provide: SharedService, useValue: mockSharedService },
        { provide: ExtensionService, useValue: mockExtensionService },
        { provide: AIAgentService, useValue: mockAIAgentService },
        { provide: BottomDrawerRef, useValue: mockDrawerRef },
        { provide: BottomDrawerService, useValue: jasmine.createSpyObj('BottomDrawerService', ['openDrawer']) }
      ]
    });

    // CoreModule (imported by the component) eagerly reaches into the c8y app-shell DI graph
    // (ApplicationService et al.) that the test injector doesn't provide (NG0201). These specs
    // exercise the component class, not the template, so strip the template/imports instead.
    TestBed.overrideComponent(MappingTypeDrawerComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });
  });

  describe('defaults on init', () => {
    it('should default to JSON / SMART_FUNCTION for inbound (both supported by JSON inbound)', async () => {
      const component = await createComponent(Direction.INBOUND);

      expect(component.formGroup.get('mappingType')?.value.value).toBe(MappingType.JSON);
      expect(component.formGroup.get('transformationType')?.value.value).toBe(TransformationType.SMART_FUNCTION);
    });

    it('should start with Expert Mode off (transformationType selector hidden, driven by the mappingType default)', async () => {
      const component = await createComponent(Direction.INBOUND);

      expect(component.formGroup.get('expertMode')?.value).toBeFalse();
      expect(component.shouldShowTransformationType()).toBeFalse();
    });
  });

  describe('expert mode toggle', () => {
    it('should enable the transformationType control and clear its value when turned on', async () => {
      const component = await createComponent(Direction.INBOUND);

      component.formGroup.get('expertMode')?.setValue(true);

      expect(component.formGroup.get('transformationType')?.enabled).toBeTrue();
      expect(component.formGroup.get('transformationType')?.value).toBeNull();
    });

    it('should re-disable and re-populate a default transformationType when turned back off', async () => {
      const component = await createComponent(Direction.INBOUND);

      component.formGroup.get('expertMode')?.setValue(true);
      component.formGroup.get('expertMode')?.setValue(false);

      expect(component.formGroup.get('transformationType')?.disabled).toBeTrue();
      expect(component.formGroup.get('transformationType')?.value?.value).toBe(TransformationType.SMART_FUNCTION);
    });
  });

  describe('extension validators', () => {
    it('should require extensionName/eventName only when transformationType is EXTENSION_JAVA', async () => {
      // JSON outbound supports EXTENSION_JAVA (unlike JSON inbound), see MappingTypeDescriptionMap.
      const component = await createComponent(Direction.OUTBOUND);
      component.formGroup.get('expertMode')?.setValue(true);

      component.formGroup.get('transformationType')?.setValue({ value: TransformationType.EXTENSION_JAVA });
      await flushPromises();

      const extensionNameControl = component.formGroup.get('extensionName');
      const eventNameControl = component.formGroup.get('eventName');
      extensionNameControl?.setValue(null);
      eventNameControl?.setValue(null);

      expect(extensionNameControl?.hasError('required')).toBeTrue();
      expect(eventNameControl?.hasError('required')).toBeTrue();

      component.formGroup.get('transformationType')?.setValue({ value: TransformationType.JSONATA });
      await flushPromises();

      expect(extensionNameControl?.hasError('required')).toBeFalse();
      expect(eventNameControl?.hasError('required')).toBeFalse();
    });
  });

  describe('onContinue / onCancel', () => {
    it('should resolve result with the selected mapping/transformation type and close the drawer', async () => {
      const component = await createComponent(Direction.INBOUND);

      component.onContinue();
      const result = await component.result;

      expect(result.mappingType).toBe(MappingType.JSON);
      expect(result.transformationType).toBe(TransformationType.SMART_FUNCTION);
      expect(mockDrawerRef.close).toHaveBeenCalled();
    });

    it('should reject result and close the drawer on cancel', async () => {
      const component = await createComponent(Direction.INBOUND);

      component.onCancel();

      await expectAsync(component.result).toBeRejected();
      expect(mockDrawerRef.close).toHaveBeenCalled();
    });

    it('should not resolve when the form is invalid (expert mode on, no transformation type chosen)', async () => {
      const component = await createComponent(Direction.INBOUND);
      component.formGroup.get('expertMode')?.setValue(true);

      component.onContinue();

      expect(mockDrawerRef.close).not.toHaveBeenCalled();
    });
  });

  describe('AI generation choice', () => {
    it('defaults to "Generate with AI" and offers the choice when an agent is deployed for the default transformation type (SMART_FUNCTION)', async () => {
      mockSharedService.getServiceConfiguration.and.resolveTo({
        ...makeServiceConfiguration(),
        smartFunctionAgent: 'agent-1'
      });
      mockAIAgentService.getAIAgents.and.resolveTo([{ name: 'agent-1' } as any]);

      const component = await createComponent(Direction.INBOUND);

      expect(component.shouldShowAIGenerationChoice()).toBeTrue();
      expect(component.formGroup.get('codeTemplateSource')?.value).toBe('ai');
      expect(component.isAIGenerationSelected()).toBeTrue();
    });

    it('does not offer the AI choice, and resolves with a code template instead, when no agent is deployed', async () => {
      mockAIAgentService.getAIAgents.and.resolveTo([]);

      const component = await createComponent(Direction.INBOUND);

      expect(component.shouldShowAIGenerationChoice()).toBeFalse();
      expect(component.formGroup.get('codeTemplateSource')?.value).toBe('template');

      component.onContinue();
      const result = await component.result;
      expect(result.generateSmartFunctionWithAI).toBeFalse();
    });

    it('does not offer the AI choice for a transformation type outside AI_GENERATION_TYPES even if a fallback (javaScriptAgent) is deployed', async () => {
      // EXTENSION_JAVA falls into resolveRequiredAgentName's default branch (javaScriptAgent) —
      // this must stay gated off regardless, since extensions never support AI generation.
      mockSharedService.getServiceConfiguration.and.resolveTo({
        ...makeServiceConfiguration(),
        javaScriptAgent: 'agent-js'
      });
      mockAIAgentService.getAIAgents.and.resolveTo([{ name: 'agent-js' } as any]);

      const component = await createComponent(Direction.OUTBOUND);
      component.formGroup.get('expertMode')?.setValue(true);
      component.formGroup.get('transformationType')?.setValue({ value: TransformationType.EXTENSION_JAVA });
      await flushPromises();

      expect(component.shouldShowAIGenerationChoice()).toBeFalse();
    });

    it('sets aiAvailabilityCheckFailed and shows a distinct warning (rather than silently treating AI as unconfigured) when the agents check itself errors', async () => {
      mockSharedService.getServiceConfiguration.and.resolveTo({
        ...makeServiceConfiguration(),
        smartFunctionAgent: 'agent-1'
      });
      mockAIAgentService.getAIAgents.and.rejectWith(new Error('network down'));

      const component = await createComponent(Direction.INBOUND);

      expect(component.aiAvailabilityCheckFailed).toBeTrue();
      expect(component.shouldShowAIAvailabilityWarning()).toBeTrue();
      expect(component.shouldShowAIGenerationChoice()).toBeFalse();
    });

    it('does not show the AI-availability warning for a transformation type that never offers AI (EXTENSION_JAVA)', async () => {
      mockAIAgentService.getAIAgents.and.rejectWith(new Error('network down'));

      const component = await createComponent(Direction.OUTBOUND);
      component.formGroup.get('expertMode')?.setValue(true);
      component.formGroup.get('transformationType')?.setValue({ value: TransformationType.EXTENSION_JAVA });
      await flushPromises();

      expect(component.aiAvailabilityCheckFailed).toBeTrue();
      expect(component.shouldShowAIAvailabilityWarning()).toBeFalse();
    });
  });
});
