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

import { StepperConfiguration } from '../../shared';
import { StepperViewModelFactory } from './stepper-view.model';

/**
 * Pure-logic unit tests for {@link StepperViewModelFactory}. The factory derives a set
 * of boolean "show*" flags from a {@link StepperConfiguration}; these flags drive the
 * stepper/unified-editor templates, so the boolean algebra here is worth pinning down
 * exhaustively. No Angular TestBed is required — the factory is a pure function.
 */
describe('StepperViewModelFactory', () => {
  /** An empty config: every optional flag is undefined. */
  const emptyConfig = (): StepperConfiguration => ({});

  describe('create() with an empty configuration', () => {
    it('defaults every show* flag sensibly when nothing is set', () => {
      const vm = StepperViewModelFactory.create(emptyConfig());

      expect(vm.showSourceEditor).toBe(false);
      expect(vm.showTargetEditor).toBe(false);
      expect(vm.showExtensionSelectors).toBe(false);
      expect(vm.showExtensionSelectorsSource).toBe(false);
      expect(vm.showExtensionSelectorsTarget).toBe(false);
      expect(vm.showInternalExtensionNote).toBe(false);
      expect(vm.showCodeEditorSection).toBe(false);
      expect(vm.showTargetContent).toBe(false);
    });

    it('shows filter controls by default (only an explicit false hides them)', () => {
      // showFilterControls is the one flag that defaults to TRUE.
      expect(StepperViewModelFactory.create(emptyConfig()).showFilterControls).toBe(true);
    });

    it('passes the original config through unchanged', () => {
      const config = emptyConfig();
      expect(StepperViewModelFactory.create(config).config).toBe(config);
    });
  });

  describe('showSourceEditor', () => {
    it('is true when showEditorSource is true', () => {
      expect(StepperViewModelFactory.create({ showEditorSource: true }).showSourceEditor).toBe(true);
    });

    it('is true when only the code editor is shown (no JSON source editor)', () => {
      // A code-based mapping has showEditorSource undefined but showCodeEditor true.
      expect(StepperViewModelFactory.create({ showCodeEditor: true }).showSourceEditor).toBe(true);
    });

    it('is false when showEditorSource is explicitly false, even if the code editor is on', () => {
      // Explicit false is a hard override: a hidden source area stays hidden.
      const vm = StepperViewModelFactory.create({ showEditorSource: false, showCodeEditor: true });
      expect(vm.showSourceEditor).toBe(false);
    });
  });

  describe('showCodeEditorSection', () => {
    it('mirrors showCodeEditor', () => {
      expect(StepperViewModelFactory.create({ showCodeEditor: true }).showCodeEditorSection).toBe(true);
      expect(StepperViewModelFactory.create({ showCodeEditor: false }).showCodeEditorSection).toBe(false);
    });
  });

  describe('showFilterControls', () => {
    it('is false only when showFilterExpression is explicitly false', () => {
      expect(StepperViewModelFactory.create({ showFilterExpression: false }).showFilterControls).toBe(false);
    });

    it('is true when showFilterExpression is true or undefined', () => {
      expect(StepperViewModelFactory.create({ showFilterExpression: true }).showFilterControls).toBe(true);
      expect(StepperViewModelFactory.create({}).showFilterControls).toBe(true);
    });
  });

  describe('extension selectors', () => {
    it('reflects source/target/internal flags individually', () => {
      const vm = StepperViewModelFactory.create({
        showProcessorExtensionsSource: true,
        showProcessorExtensionsTarget: false,
        showProcessorExtensionsInternal: false
      });
      expect(vm.showExtensionSelectorsSource).toBe(true);
      expect(vm.showExtensionSelectorsTarget).toBe(false);
      expect(vm.showInternalExtensionNote).toBe(false);
    });

    it('showExtensionSelectors is the OR of source, target and internal', () => {
      expect(
        StepperViewModelFactory.create({ showProcessorExtensionsSource: true }).showExtensionSelectors
      ).toBe(true);
      expect(
        StepperViewModelFactory.create({ showProcessorExtensionsTarget: true }).showExtensionSelectors
      ).toBe(true);
      expect(
        StepperViewModelFactory.create({ showProcessorExtensionsInternal: true }).showExtensionSelectors
      ).toBe(true);
      expect(StepperViewModelFactory.create({}).showExtensionSelectors).toBe(false);
    });
  });

  describe('showTargetContent', () => {
    it('is true when the target editor is shown', () => {
      expect(StepperViewModelFactory.create({ showEditorTarget: true }).showTargetContent).toBe(true);
    });

    it('is true when target extension selectors are shown (without a target editor)', () => {
      const vm = StepperViewModelFactory.create({ showProcessorExtensionsTarget: true });
      expect(vm.showTargetContent).toBe(true);
    });

    it('is true when the internal extension note is shown', () => {
      expect(
        StepperViewModelFactory.create({ showProcessorExtensionsInternal: true }).showTargetContent
      ).toBe(true);
    });

    it('is false when no target editor, target extensions, or internal note are configured', () => {
      // Source-only extensions must NOT light up target content.
      const vm = StepperViewModelFactory.create({ showProcessorExtensionsSource: true });
      expect(vm.showTargetContent).toBe(false);
    });
  });

  it('produces a fresh view model per call (no shared mutable state)', () => {
    const a = StepperViewModelFactory.create({ showEditorSource: true });
    const b = StepperViewModelFactory.create({ showEditorSource: false });
    expect(a.showSourceEditor).toBe(true);
    expect(b.showSourceEditor).toBe(false);
  });
});
