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

import { StepperConfiguration } from '../../shared/mapping/mapping.model';

/**
 * View model for the stepper template that consolidates complex conditional
 * logic into computed properties. This simplifies the template by reducing
 * the number of conditional checks and making the intent clearer.
 */
export interface StepperViewModel {
  /**
   * Show the source editor (either JSON editor or code editor)
   */
  readonly showSourceEditor: boolean;

  /**
   * Show the target editor
   */
  readonly showTargetEditor: boolean;

  /**
   * Show extension selectors (either source or target extensions)
   */
  readonly showExtensionSelectors: boolean;

  /**
   * Show the extension selectors specifically for source
   */
  readonly showExtensionSelectorsSource: boolean;

  /**
   * Show the extension selectors specifically for target
   */
  readonly showExtensionSelectorsTarget: boolean;

  /**
   * Show internal extension note
   */
  readonly showInternalExtensionNote: boolean;

  /**
   * Show filter controls and expression
   */
  readonly showFilterControls: boolean;

  /**
   * Show the code editor section (JavaScript/GraalJS)
   */
  readonly showCodeEditorSection: boolean;

  /**
   * Show any target content (editor or extension selectors)
   */
  readonly showTargetContent: boolean;

  /**
   * Pass-through for direct access to underlying configuration
   */
  readonly config: StepperConfiguration;
}

/**
 * Factory for creating StepperViewModel instances from StepperConfiguration.
 * Consolidates complex OR conditions and boolean logic into clear, semantic properties.
 */
export class StepperViewModelFactory {
  /**
   * Creates a view model from a stepper configuration.
   *
   * @param config - The stepper configuration
   * @returns A view model with computed properties for template usage
   */
  static create(config: StepperConfiguration): StepperViewModel {
    // Compute derived properties once instead of in every template check
    const showExtensionSelectorsSource =
      config.showProcessorExtensionsSource || false;
    const showExtensionSelectorsTarget =
      config.showProcessorExtensionsTarget || false;
    const showInternalExtensionNote =
      config.showProcessorExtensionsInternal || false;

    const showExtensionSelectors =
      showExtensionSelectorsSource ||
      showExtensionSelectorsTarget ||
      showInternalExtensionNote;

    const showTargetContent =
      config.showEditorTarget ||
      showExtensionSelectorsTarget ||
      showInternalExtensionNote ||
      false;

    return {
      // Consolidate OR conditions into computed properties
      showSourceEditor: config.showEditorSource || config.showCodeEditor || false,
      showTargetEditor: config.showEditorTarget || false,
      showExtensionSelectors,
      showExtensionSelectorsSource,
      showExtensionSelectorsTarget,
      showInternalExtensionNote,
      showFilterControls: config.showFilterExpression !== false,
      showCodeEditorSection: config.showCodeEditor || false,
      showTargetContent,

      // Pass through original config for edge cases
      config
    };
  }
}
