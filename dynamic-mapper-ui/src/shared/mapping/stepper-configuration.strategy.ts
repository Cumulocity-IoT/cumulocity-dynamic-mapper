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

import { Direction, MappingType, StepperConfiguration, TransformationType } from './mapping.model';
import { MappingPersistence } from './stepper.model';

export interface StepperConfigurationContext {
  mappingType: MappingType;
  transformationType: TransformationType;
  direction: Direction;
  persistence: MappingPersistence;
  readOnly?: boolean;
  isCopy?: boolean;
  substitutionsAsCode: boolean;
  hasDeployedConnector?: boolean;
}

export interface StepperConfigurationOverride {
  condition: (ctx: StepperConfigurationContext) => boolean;
  /** Static properties, or a function when the value depends on the context. */
  properties: Partial<StepperConfiguration> | ((ctx: StepperConfigurationContext) => Partial<StepperConfiguration>);
}

/** Properties common to Java extension mappings in both directions. */
const JAVA_EXTENSION_PROPERTIES: Partial<StepperConfiguration> = {
  showEditorTarget: false,
  allowTestSending: true,
  allowTestTransformation: true,
  allowDefiningSubstitutions: false,
  allowNoDefinedIdentifier: true,
  advanceFromStepToEndStep: 2
};

/**
 * Ordered list of configuration overrides.
 * Later overrides take precedence over earlier ones.
 * Each override is applied in sequence if its condition matches.
 */
const CONFIGURATION_OVERRIDES: StepperConfigurationOverride[] = [
  {
    // Test sending publishes to a broker, so outbound needs a deployed connector. Later rules
    // (substitutions as code, Smart Function, Java) enable it regardless.
    condition: (ctx) => ctx.direction === Direction.OUTBOUND,
    properties: (ctx) => ({ allowTestSending: ctx.hasDeployedConnector === true })
  },
  {
    condition: (ctx) => ctx.substitutionsAsCode,
    properties: {
      showCodeEditor: true,
      allowTestSending: true,
      allowTestTransformation: true
      // Note: advanceFromStepToEndStep intentionally not set here
      // It will be removed in post-processing if substitutionsAsCode is true
    }
  },
  {
    condition: (ctx) => ctx.transformationType === TransformationType.SMART_FUNCTION,
    properties: {
      showEditorTarget: false,
      allowTestSending: true,
      allowTestTransformation: true
    }
  },
  {
    condition: (ctx) => ctx.transformationType === TransformationType.EXTENSION_JAVA,
    properties: JAVA_EXTENSION_PROPERTIES
  },
  {
    condition: (ctx) =>
      ctx.transformationType === TransformationType.EXTENSION_JAVA &&
      ctx.direction === Direction.OUTBOUND,
    properties: { showProcessorExtensionsTarget: true }
  },
  {
    condition: (ctx) =>
      ctx.transformationType === TransformationType.EXTENSION_JAVA &&
      ctx.direction === Direction.INBOUND,
    properties: { showProcessorExtensionsSource: true, showFilterExpression: false }
  },
  {
    // Expansion shows the payload structure, which eases defining substitutions and code.
    condition: (ctx) =>
      ctx.transformationType === TransformationType.SMART_FUNCTION ||
      ctx.transformationType === TransformationType.JSONATA ||
      ctx.transformationType === TransformationType.EXTENSION_JAVA,
    properties: { allowTemplateExpansion: true }
  }
];

/**
 * Resolver for stepper configuration that applies overrides in a predictable,
 * debuggable manner.
 */
export class StepperConfigurationResolver {
  /**
   * Resolves the final stepper configuration by applying overrides in order.
   * This makes the precedence rules explicit and debuggable.
   *
   * @param baseConfig - Base configuration from mapping type
   * @param context - Current mapping context
   * @returns Resolved configuration with all applicable overrides applied
   */
  static resolve(
    baseConfig: StepperConfiguration,
    context: StepperConfigurationContext
  ): StepperConfiguration {
    // Start with base configuration from mapping type
    let config: StepperConfiguration = {
      ...baseConfig,
      direction: context.direction,
      persistence: context.persistence,
      readOnly: context.readOnly ?? false,
      isCopy: context.isCopy ?? false
    };

    // Apply each override that matches its condition
    for (const override of CONFIGURATION_OVERRIDES) {
      if (override.condition(context)) {
        const props = typeof override.properties === 'function' ? override.properties(context) : override.properties;
        config = { ...config, ...props };
      }
    }

    // Handle the special case: substitutionsAsCode clears advanceFromStepToEndStep
    // This is done as post-processing because the substitutionsAsCode override
    // doesn't set this property, allowing other overrides to potentially set it
    if (context.substitutionsAsCode && config.advanceFromStepToEndStep !== undefined) {
      const { advanceFromStepToEndStep, ...rest } = config;
      config = rest as StepperConfiguration;
    }

    return config;
  }

  /**
   * Debug helper: returns indices of overrides that were applied.
   * Useful for troubleshooting configuration issues.
   *
   * @param context - Current mapping context
   * @returns Array of override indices that matched
   */
  static getAppliedOverrides(context: StepperConfigurationContext): number[] {
    return CONFIGURATION_OVERRIDES
      .map((override, index) => (override.condition(context) ? index : -1))
      .filter((index) => index !== -1);
  }

  /**
   * Debug helper: returns human-readable descriptions of applied overrides.
   *
   * @param context - Current mapping context
   * @returns Array of descriptions for debugging
   */
  static getAppliedOverrideDescriptions(context: StepperConfigurationContext): string[] {
    const descriptions: string[] = [];
    const indices = this.getAppliedOverrides(context);

    for (const index of indices) {
      const override = CONFIGURATION_OVERRIDES[index];
      const props = Object.keys(
        typeof override.properties === 'function' ? override.properties(context) : override.properties
      ).join(', ');
      descriptions.push(`Override ${index}: sets ${props}`);
    }

    if (context.substitutionsAsCode) {
      descriptions.push('Post-processing: removed advanceFromStepToEndStep');
    }

    return descriptions;
  }
}
