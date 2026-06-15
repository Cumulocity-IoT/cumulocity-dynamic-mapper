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

import { inject } from '@angular/core';
import { ResolveFn } from '@angular/router';
import {
  DeploymentMapEntry,
  Direction,
  isSubstitutionsAsCode,
  Mapping,
  MappingTypeDescriptionMap,
  StepperConfiguration,
  TransformationType
} from '../../shared';
import {
  StepperConfigurationContext,
  StepperConfigurationResolver
} from '../../shared/mapping/stepper-configuration.strategy';
import { EditorMode } from '../shared/stepper.model';
import { MappingService } from './mapping.service';

export interface MappingEditData {
  mapping: Mapping;
  stepperConfiguration: StepperConfiguration;
  deploymentMapEntry: DeploymentMapEntry;
}

export const mappingEditResolver: ResolveFn<MappingEditData> = async (route) => {
  const mappingService = inject(MappingService);
  const identifier = route.paramMap.get('identifier');
  const direction = route.data['direction'] as Direction;

  const [mappings, deploymentMapEntry] = await Promise.all([
    mappingService.getMappings(direction),
    mappingService.getDefinedDeploymentMapEntry(identifier)
  ]);

  const mapping = mappings.find(m => m.identifier === identifier);
  if (!mapping) {
    throw new Error(`Mapping with identifier ${identifier} not found`);
  }

  // Edits go to the line's draft (D-8). If a draft already exists, continue editing it
  // (preserving its optimistic-concurrency token); otherwise start from the active config.
  const draft = await mappingService.getDraft(mapping.id);
  const editable = draft ?? mapping;

  // Deprecated SUBSTITUTION_AS_CODE mappings are always read-only (export/delete only).
  // Active mappings are no longer read-only: editing them saves to a draft and never
  // changes the running configuration until the draft is published and activated.
  // eslint-disable-next-line deprecation/deprecation
  const isDeprecated = mapping.transformationType === TransformationType.SUBSTITUTION_AS_CODE;

  const context: StepperConfigurationContext = {
    mappingType: editable.mappingType,
    transformationType: editable.transformationType,
    direction,
    editorMode: isDeprecated ? EditorMode.READ_ONLY : EditorMode.UPDATE,
    substitutionsAsCode: isSubstitutionsAsCode(editable),
    hasDeployedConnector: deploymentMapEntry.connectors.length > 0
  };

  const stepperConfiguration = StepperConfigurationResolver.resolve(
    MappingTypeDescriptionMap[editable.mappingType].stepperConfiguration,
    context
  );

  return {
    mapping: structuredClone(editable),
    stepperConfiguration,
    deploymentMapEntry
  };
};
