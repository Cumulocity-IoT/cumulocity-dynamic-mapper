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

import { ServiceConfigurationComponent } from './service-configuration.component';

/**
 * Expert-mode visibility and the cross-field budget validation.
 *
 * These are plain class-level concerns, so the component is exercised without TestBed — its
 * template pulls in the c8y app-shell DI graph the test injector does not provide.
 */
describe('ServiceConfigurationComponent', () => {
  const STORAGE_KEY = 'dynamic-mapper.serviceConfiguration.expertMode';

  function componentOn(section: string): ServiceConfigurationComponent {
    const component = Object.create(
      ServiceConfigurationComponent.prototype
    ) as ServiceConfigurationComponent;
    component.expertMode = false;
    (component as any).section = section;
    return component;
  }

  beforeEach(() => localStorage.removeItem(STORAGE_KEY));
  afterEach(() => localStorage.removeItem(STORAGE_KEY));

  describe('expert mode', () => {
    it('is off by default, so a tab shows only what an administrator has to decide', () => {
      expect(componentOn('processing').expertMode).toBeFalse();
    });

    it('reports how many settings the current tab is hiding', () => {
      // GraalVM engine sizing: supportESM, rotation threshold, max age, context pool size.
      expect(componentOn('processing').hiddenExpertCount).toBe(4);
      expect(componentOn('caching').hiddenExpertCount).toBe(1);
      expect(componentOn('monitoring').hiddenExpertCount).toBe(2);
    });

    it('reports nothing hidden on tabs that hide nothing', () => {
      // A count of everything hidden would be misleading here — General hides no setting.
      expect(componentOn('general').hiddenExpertCount).toBe(0);
      expect(componentOn('ai').hiddenExpertCount).toBe(0);
    });

    it('persists the choice so it does not have to be re-enabled on every visit', () => {
      const component = componentOn('processing');

      component.toggleExpertMode();

      expect(component.expertMode).toBeTrue();
      expect(localStorage.getItem(STORAGE_KEY)).toBe('true');
    });

    it('restores a previously enabled expert mode', () => {
      localStorage.setItem(STORAGE_KEY, 'true');
      const component = componentOn('processing');

      (component as any).restoreExpertMode();

      expect(component.expertMode).toBeTrue();
    });

    it('falls back to off when nothing was stored', () => {
      const component = componentOn('processing');

      (component as any).restoreExpertMode();

      expect(component.expertMode).toBeFalse();
    });
  });

  describe('processing budget validation', () => {
    function group(cpu: unknown, pipeline: unknown) {
      return {
        get: (name: string) => ({ value: name === 'maxCPUTimeMS' ? cpu : pipeline })
      } as any;
    }

    it('accepts a pipeline timeout above the CPU budget', () => {
      expect(
        ServiceConfigurationComponent.pipelineTimeoutExceedsCpuBudget(group(5000, 8000))
      ).toBeNull();
    });

    it('rejects a pipeline timeout that would make the CPU budget unreachable', () => {
      // The callback would cancel the pipeline before the JS could ever hit its own limit.
      expect(
        ServiceConfigurationComponent.pipelineTimeoutExceedsCpuBudget(group(10000, 2000))
      ).toEqual({ pipelineTimeoutTooSmall: { cpu: 10000, pipeline: 2000 } });
    });

    it('rejects equal budgets', () => {
      expect(
        ServiceConfigurationComponent.pipelineTimeoutExceedsCpuBudget(group(5000, 5000))
      ).not.toBeNull();
    });

    it('stays quiet while a field is empty or the CPU budget is disabled', () => {
      expect(ServiceConfigurationComponent.pipelineTimeoutExceedsCpuBudget(group('', 8000))).toBeNull();
      expect(ServiceConfigurationComponent.pipelineTimeoutExceedsCpuBudget(group(0, 8000))).toBeNull();
    });
  });
});
