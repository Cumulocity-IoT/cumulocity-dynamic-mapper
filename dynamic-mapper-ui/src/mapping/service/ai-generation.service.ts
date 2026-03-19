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

import { inject, Injectable } from '@angular/core';
import { AlertService, BottomDrawerService } from '@c8y/ngx-components';
import { Mapping, isSubstitutionsAsCode, StepperConfiguration } from '../../shared';
import { AIPromptComponent } from '../prompt/ai-prompt.component';
import { AgentObjectDefinition, AgentTextDefinition } from '../shared/ai-prompt.model';
import { SubstitutionManagementService } from './substitution-management.service';
import { MappingStepperService } from './mapping-stepper.service';

/**
 * Result from AI generation process
 */
export interface AIGenerationResult {
  /** Generated code (for SUBSTITUTION_AS_CODE transformation type) */
  code?: string;
  /** Generated substitutions (for standard transformation types) */
  substitutions?: any[];
  /** Whether the generation was successful */
  success: boolean;
  /** Error message if generation failed */
  error?: string;
}

/**
 * Service responsible for managing AI-powered generation of substitutions and code.
 * Encapsulates the logic for opening the AI drawer and processing results.
 */
@Injectable()
export class AIGenerationService {
  private readonly bottomDrawerService = inject(BottomDrawerService);
  private readonly alertService = inject(AlertService);

  /**
   * Opens the AI generation drawer and processes the results
   * @param mapping The mapping configuration with templates
   * @param aiAgent The AI agent configuration to use
   * @param sourceTemplate The source template JSON
   * @param targetTemplate The target template JSON
   * @returns Promise with the generation result
   */
  async generateWithAI(
    mapping: Mapping,
    aiAgent: AgentObjectDefinition | AgentTextDefinition | null,
    sourceTemplate: any,
    targetTemplate: any
  ): Promise<AIGenerationResult> {
    try {
      // Create a test mapping with stringified templates
      const testMapping = structuredClone(mapping);
      testMapping.sourceTemplate = JSON.stringify(sourceTemplate);
      testMapping.targetTemplate = JSON.stringify(targetTemplate);

      // Open the AI drawer
      const drawer = this.bottomDrawerService.openDrawer(AIPromptComponent, {
        initialState: { mapping: testMapping, aiAgent }
      });

      // Wait for the result
      const result = await drawer.instance.result;

      // Process based on transformation type
      if (isSubstitutionsAsCode(mapping)) {
        return this.processCodeGeneration(result);
      } else {
        return this.processSubstitutionGeneration(result);
      }
    } catch (error) {
      console.error('AI generation error:', error);
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Unknown error occurred'
      };
    }
  }

  /**
   * Process AI-generated JavaScript code
   */
  private processCodeGeneration(result: any): AIGenerationResult {
    if (typeof result === 'string' && result.trim()) {
      this.alertService.success('Generated JavaScript code successfully.');
      return {
        success: true,
        code: result
      };
    } else {
      return {
        success: false,
        error: 'No valid JavaScript code was generated.'
      };
    }
  }

  /**
   * Process AI-generated substitutions
   */
  private processSubstitutionGeneration(result: any): AIGenerationResult {
    if (Array.isArray(result) && result.length > 0) {
      this.alertService.success(`Generated ${result.length} substitutions.`);
      return {
        success: true,
        substitutions: result
      };
    } else {
      return {
        success: false,
        error: 'No substitutions were generated.'
      };
    }
  }

  /**
   * Apply generated substitutions to a mapping
   * @param mapping The mapping to add substitutions to
   * @param substitutions The generated substitutions
   * @param substitutionService Service for managing substitutions
   * @param stepperService Service for updating validity
   * @param stepperConfiguration Configuration for the stepper
   * @param expertMode Whether expert mode is enabled
   * @param currentStepIndex Current step in the wizard
   */
  applyGeneratedSubstitutions(
    mapping: Mapping,
    substitutions: any[],
    substitutionService: SubstitutionManagementService,
    stepperService: MappingStepperService,
    stepperConfiguration: StepperConfiguration,
    expertMode: boolean,
    currentStepIndex: number
  ): void {
    // Clear existing substitutions
    mapping.substitutions.splice(0);

    // Add new substitutions
    substitutions.forEach(sub => {
      substitutionService.addSubstitution(
        sub,
        mapping,
        stepperConfiguration,
        expertMode,
        () => {
          stepperService.updateSubstitutionValidity(
            mapping,
            stepperConfiguration.allowNoDefinedIdentifier,
            currentStepIndex,
            stepperConfiguration.showCodeEditor
          );
        }
      );
    });
  }
}
