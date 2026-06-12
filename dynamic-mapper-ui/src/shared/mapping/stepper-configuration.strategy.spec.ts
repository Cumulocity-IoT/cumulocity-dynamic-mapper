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

import {
  StepperConfigurationContext,
  StepperConfigurationResolver
} from './stepper-configuration.strategy';
import { Direction, MappingType, StepperConfiguration, TransformationType } from './mapping.model';
import { EditorMode } from '../../mapping/shared/stepper.model';

describe('StepperConfigurationResolver', () => {
  describe('resolve', () => {
    it('should preserve base configuration when no overrides match', () => {
      const baseConfig: StepperConfiguration = {
        showEditorSource: true,
        showEditorTarget: true,
        allowTestSending: true,
        allowTestTransformation: true
      };

      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.DEFAULT,
        direction: Direction.INBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: false
      };

      const result = StepperConfigurationResolver.resolve(baseConfig, context);

      expect(result.showEditorSource).toBe(true);
      expect(result.showEditorTarget).toBe(true);
      expect(result.allowTestSending).toBe(true);
      expect(result.allowTestTransformation).toBe(true);
      expect(result.direction).toBe(Direction.INBOUND);
      expect(result.editorMode).toBe(EditorMode.CREATE);
    });

    it('should apply outbound override', () => {
      const baseConfig: StepperConfiguration = {
        allowTestSending: true,
        allowTestTransformation: true
      };

      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.DEFAULT,
        direction: Direction.OUTBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: false
      };

      const result = StepperConfigurationResolver.resolve(baseConfig, context);

      expect(result.allowTestSending).toBe(false);
      expect(result.allowTestTransformation).toBe(true); // unchanged
    });

    it('should apply substitutionsAsCode override', () => {
      const baseConfig: StepperConfiguration = {
        showCodeEditor: false,
        allowTestSending: true,
        allowTestTransformation: false
      };

      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.SUBSTITUTION_AS_CODE,
        direction: Direction.INBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: true
      };

      const result = StepperConfigurationResolver.resolve(baseConfig, context);

      expect(result.showCodeEditor).toBe(true);
      expect(result.allowTestSending).toBe(true);
      expect(result.allowTestTransformation).toBe(true);
    });

    it('should apply smart function override', () => {
      const baseConfig: StepperConfiguration = {
        showEditorTarget: true,
        allowTestSending: true,
        allowTestTransformation: false
      };

      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.SMART_FUNCTION,
        direction: Direction.INBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: false
      };

      const result = StepperConfigurationResolver.resolve(baseConfig, context);

      expect(result.showEditorTarget).toBe(false);
      expect(result.allowTestSending).toBe(true);
      expect(result.allowTestTransformation).toBe(true);
    });

    it('should apply Java extension outbound override', () => {
      const baseConfig: StepperConfiguration = {
        showProcessorExtensionsTarget: false,
        showEditorTarget: true,
        allowTestSending: true,
        allowTestTransformation: true,
        advanceFromStepToEndStep: undefined
      };

      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.EXTENSION_JAVA,
        direction: Direction.OUTBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: false
      };

      const result = StepperConfigurationResolver.resolve(baseConfig, context);

      expect(result.showProcessorExtensionsTarget).toBe(true);
      expect(result.showEditorTarget).toBe(false);
      expect(result.allowTestSending).toBe(true);
      expect(result.allowTestTransformation).toBe(true);
      expect(result.advanceFromStepToEndStep).toBe(2);
    });

    it('should apply Java extension inbound override', () => {
      const baseConfig: StepperConfiguration = {
        showEditorTarget: true,
        showFilterExpression: true,
        allowTestSending: true,
        allowTestTransformation: true
      };

      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.EXTENSION_JAVA,
        direction: Direction.INBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: false
      };

      const result = StepperConfigurationResolver.resolve(baseConfig, context);

      expect(result.showEditorTarget).toBe(false);
      expect(result.showFilterExpression).toBe(false);
      expect(result.allowTestSending).toBe(true);
      expect(result.allowTestTransformation).toBe(true);
    });

    it('should apply mapping type EXTENSION_JAVA inbound override', () => {
      const baseConfig: StepperConfiguration = {
        showEditorTarget: true,
        showFilterExpression: true
      };

      const context: StepperConfigurationContext = {
        mappingType: MappingType.EXTENSION_JAVA,
        transformationType: TransformationType.DEFAULT,
        direction: Direction.INBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: false
      };

      const result = StepperConfigurationResolver.resolve(baseConfig, context);

      // No override exists for the deprecated MappingType.EXTENSION_JAVA itself;
      // its stepperConfiguration in MappingTypeDescriptionMap already has the
      // correct defaults. The DEFAULT transformation override sets allowTemplateExpansion.
      expect(result.showEditorTarget).toBe(true);  // unchanged by overrides
      expect(result.showFilterExpression).toBe(true);  // unchanged by overrides
      expect(result.allowTemplateExpansion).toBe(false);  // set by DEFAULT transformation override
    });

    it('should handle multiple overlapping overrides correctly', () => {
      const baseConfig: StepperConfiguration = {
        showEditorTarget: true,
        allowTestSending: true,
        allowTestTransformation: false
      };

      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.SMART_FUNCTION,
        direction: Direction.INBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: true
      };

      const result = StepperConfigurationResolver.resolve(baseConfig, context);

      // Both smart function and substitutionsAsCode set allowTestTransformation: true
      // Later override (smart function) should win
      expect(result.allowTestTransformation).toBe(true);
      // Smart function sets showEditorTarget: false (overrides base)
      expect(result.showEditorTarget).toBe(false);
      // substitutionsAsCode sets showCodeEditor: true
      expect(result.showCodeEditor).toBe(true);
      // Both smart function and substitutionsAsCode overrides set allowTestSending: true
      expect(result.allowTestSending).toBe(true);
    });

    it('should remove advanceFromStepToEndStep when substitutionsAsCode is true', () => {
      const baseConfig: StepperConfiguration = {
        advanceFromStepToEndStep: 2
      };

      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.SUBSTITUTION_AS_CODE,
        direction: Direction.INBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: true
      };

      const result = StepperConfigurationResolver.resolve(baseConfig, context);

      expect(result.advanceFromStepToEndStep).toBeUndefined();
    });

    it('should not remove advanceFromStepToEndStep when substitutionsAsCode is false', () => {
      const baseConfig: StepperConfiguration = {
        advanceFromStepToEndStep: 2
      };

      const context: StepperConfigurationContext = {
        mappingType: MappingType.PROTOBUF_INTERNAL,
        transformationType: TransformationType.DEFAULT,
        direction: Direction.INBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: false
      };

      const result = StepperConfigurationResolver.resolve(baseConfig, context);

      expect(result.advanceFromStepToEndStep).toBe(2);
    });

    it('should apply overrides in correct precedence order', () => {
      const baseConfig: StepperConfiguration = {
        allowTestSending: true
      };

      // This context matches both outbound override and Java extension outbound override
      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.EXTENSION_JAVA,
        direction: Direction.OUTBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: false
      };

      const result = StepperConfigurationResolver.resolve(baseConfig, context);

      // The EXTENSION_JAVA outbound override (later) sets allowTestSending: true,
      // overriding the generic outbound override (earlier) that set it to false.
      expect(result.allowTestSending).toBe(true);
      // Java extension override also sets advanceFromStepToEndStep: 2
      expect(result.advanceFromStepToEndStep).toBe(2);
    });
  });

  describe('getAppliedOverrides', () => {
    it('should return only the DEFAULT transformation override for JSON DEFAULT INBOUND', () => {
      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.DEFAULT,
        direction: Direction.INBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: false
      };

      const appliedOverrides = StepperConfigurationResolver.getAppliedOverrides(context);

      // Only the DEFAULT transformation override (allowTemplateExpansion: false) matches
      expect(appliedOverrides.length).toBe(1);
    });

    it('should include outbound and DEFAULT semantics for outbound JSON DEFAULT', () => {
      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.DEFAULT,
        direction: Direction.OUTBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: false
      };

      const appliedOverrides = StepperConfigurationResolver.getAppliedOverrides(context);
      const descriptions = StepperConfigurationResolver.getAppliedOverrideDescriptions(context);
      const result = StepperConfigurationResolver.resolve({ allowTestSending: true }, context);

      // Two overrides should match semantically: outbound + DEFAULT transformation.
      expect(appliedOverrides.length).toBe(2);
      expect(descriptions.some(d => d.includes('allowTestSending'))).toBe(true);
      expect(descriptions.some(d => d.includes('allowTemplateExpansion'))).toBe(true);
      expect(result.allowTestSending).toBe(false);
      expect(result.allowTemplateExpansion).toBe(false);
    });

    it('should include semantic overrides for smart function with substitutionsAsCode', () => {
      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.SMART_FUNCTION,
        direction: Direction.INBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: true
      };

      const appliedOverrides = StepperConfigurationResolver.getAppliedOverrides(context);
      const descriptions = StepperConfigurationResolver.getAppliedOverrideDescriptions(context);
      const result = StepperConfigurationResolver.resolve(
        {
          showEditorTarget: true,
          allowTestSending: false,
          allowTestTransformation: false,
          showCodeEditor: false,
          allowTemplateExpansion: false,
          advanceFromStepToEndStep: 2
        },
        context
      );

      // Should match substitutionsAsCode + smart function behavior (two SMART_FUNCTION overrides).
      expect(appliedOverrides.length).toBe(3);
      expect(descriptions.some(d => d.includes('showCodeEditor'))).toBe(true);
      expect(descriptions.some(d => d.includes('showEditorTarget'))).toBe(true);
      expect(descriptions.some(d => d.includes('allowTemplateExpansion'))).toBe(true);
      expect(result.showCodeEditor).toBe(true);
      expect(result.showEditorTarget).toBe(false);
      expect(result.allowTestSending).toBe(true);
      expect(result.allowTestTransformation).toBe(true);
      expect(result.allowTemplateExpansion).toBe(true);
      expect(result.advanceFromStepToEndStep).toBeUndefined();
    });
  });

  describe('getAppliedOverrideDescriptions', () => {
    it('should return descriptive messages for applied overrides', () => {
      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.DEFAULT,
        direction: Direction.OUTBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: false
      };

      const descriptions = StepperConfigurationResolver.getAppliedOverrideDescriptions(context);

      expect(descriptions.length).toBeGreaterThan(0);
      expect(descriptions[0]).toContain('Override');
      expect(descriptions[0]).toContain('allowTestSending');
    });

    it('should include post-processing message for substitutionsAsCode', () => {
      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.SUBSTITUTION_AS_CODE,
        direction: Direction.INBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: true
      };

      const descriptions = StepperConfigurationResolver.getAppliedOverrideDescriptions(context);

      const postProcessingMessage = descriptions.find(d =>
        d.includes('Post-processing') && d.includes('advanceFromStepToEndStep')
      );
      expect(postProcessingMessage).toBeDefined();
    });
  });
});
