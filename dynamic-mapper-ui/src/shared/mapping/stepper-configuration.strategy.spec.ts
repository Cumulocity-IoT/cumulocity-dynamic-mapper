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
import { Direction, MappingType, StepperConfiguration, TransformationType,  SnoopStatus} from './mapping.model';
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

    it('should apply snoop status override for outbound with snoop enabled', () => {
      const baseConfig: StepperConfiguration = {
        advanceFromStepToEndStep: undefined
      };

      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.DEFAULT,
        direction: Direction.OUTBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: false,
        snoopStatus: SnoopStatus.ENABLED
      };

      const result = StepperConfigurationResolver.resolve(baseConfig, context);

      expect(result.advanceFromStepToEndStep).toBe(0);
    });

    it('should not apply snoop status override when snoop is not enabled', () => {
      const baseConfig: StepperConfiguration = {
        advanceFromStepToEndStep: undefined
      };

      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.DEFAULT,
        direction: Direction.OUTBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: false,
        snoopStatus: SnoopStatus.STARTED
      };

      const result = StepperConfigurationResolver.resolve(baseConfig, context);

      expect(result.advanceFromStepToEndStep).toBeUndefined();
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
      expect(result.allowTestSending).toBe(false);
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
      expect(result.allowTestSending).toBe(false);
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
      expect(result.allowTestSending).toBe(false);
      expect(result.allowTestTransformation).toBe(false);
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
      expect(result.allowTestSending).toBe(false);
      expect(result.allowTestTransformation).toBe(false);
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

      expect(result.showEditorTarget).toBe(false);
      expect(result.showFilterExpression).toBe(false);
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
      // Both set allowTestSending: false
      expect(result.allowTestSending).toBe(false);
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

      // Both overrides set allowTestSending: false, so it should be false
      expect(result.allowTestSending).toBe(false);
      // Java extension override also sets advanceFromStepToEndStep: 2
      expect(result.advanceFromStepToEndStep).toBe(2);
    });
  });

  describe('getAppliedOverrides', () => {
    it('should return empty array when no overrides match', () => {
      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.DEFAULT,
        direction: Direction.INBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: false
      };

      const appliedOverrides = StepperConfigurationResolver.getAppliedOverrides(context);

      expect(appliedOverrides).toEqual([]);
    });

    it('should return correct indices for outbound', () => {
      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.DEFAULT,
        direction: Direction.OUTBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: false
      };

      const appliedOverrides = StepperConfigurationResolver.getAppliedOverrides(context);

      expect(appliedOverrides).toContain(0); // outbound override
    });

    it('should return correct indices for multiple overrides', () => {
      const context: StepperConfigurationContext = {
        mappingType: MappingType.JSON,
        transformationType: TransformationType.SMART_FUNCTION,
        direction: Direction.INBOUND,
        editorMode: EditorMode.CREATE,
        substitutionsAsCode: true
      };

      const appliedOverrides = StepperConfigurationResolver.getAppliedOverrides(context);

      expect(appliedOverrides).toContain(2); // substitutionsAsCode override
      expect(appliedOverrides).toContain(3); // smart function override
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
