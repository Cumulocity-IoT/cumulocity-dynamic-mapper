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

import { Injectable, inject } from '@angular/core';
import { AlertService } from '@c8y/ngx-components';
import { Substitution, Mapping } from '../../shared';
import { SubstitutionModel } from '../shared/stepper.model';

@Injectable()
export class SubstitutionManagementService {
  private alertService = inject(AlertService);

  isSubstitutionValid(substitutionModel: SubstitutionModel): boolean {
    const { sourceExpression, targetExpression, pathSource, pathTarget } = substitutionModel;
    return sourceExpression?.valid &&
           targetExpression?.valid &&
           pathSource !== '' &&
           pathTarget !== '';
  }

  /**
   * Bulk-replaces all substitutions in one shot — appropriate for programmatic replacement
   * (e.g. applying a freshly AI-generated set) where the caller means to replace the full set
   * atomically, without the per-item duplicate handling (and its alert) that
   * `addSubstitution()` applies.
   */
  replaceAllSubstitutions(
    substitutionModels: SubstitutionModel[],
    mapping: Mapping,
    onSuccess: () => void
  ): void {
    const substitutions = substitutionModels.map(model => this.toSubstitution(model));
    mapping.substitutions.splice(0, mapping.substitutions.length, ...substitutions);
    onSuccess();
  }

  private toSubstitution(substitutionModel: SubstitutionModel): Substitution {
    // Strip to a plain Substitution: substitutionModel also carries transient UI-only state
    // (stepperConfiguration, sourceExpression/targetExpression, path*IsExpression) that must not
    // leak into the persisted mapping.
    return {
      pathSource: substitutionModel.pathSource,
      pathTarget: substitutionModel.pathTarget,
      repairStrategy: substitutionModel.repairStrategy,
      expandArray: substitutionModel.expandArray
    };
  }

  /**
   * A substitution whose `pathTarget` already exists replaces that entry in place: two
   * substitutions writing the same target path are never both meaningful, and the last one
   * defined is what the user just asked for. This used to open a confirmation modal; the
   * substitution grid now shows the full set inline (including expandArray/repairStrategy),
   * so the replaced row is visible right there and an alert is enough to flag what happened.
   */
  addSubstitution(
    substitutionModel: SubstitutionModel,
    mapping: Mapping,
    onSuccess: () => void
  ): void {
    const substitution: Substitution = this.toSubstitution(substitutionModel);
    const duplicateIndex = mapping.substitutions.findIndex(
      sub => sub.pathTarget === substitution.pathTarget
    );

    if (duplicateIndex === -1) {
      mapping.substitutions.push(substitution);
    } else {
      mapping.substitutions[duplicateIndex] = substitution;
      this.alertService.info(
        `Replaced substitution # ${duplicateIndex + 1}: it already targeted [${substitution.pathTarget}].`
      );
    }

    onSuccess();
  }

  /**
   * Re-pointing pathSource/pathTarget can collide with a *different* substitution's pathTarget.
   * As in addSubstitution(), the two collapse into a single entry at the colliding index rather
   * than leaving two substitutions writing the same target, and an alert reports it.
   */
  updateSubstitution(
    selectedSubstitution: number,
    substitutionModel: SubstitutionModel,
    mapping: Mapping,
    onSuccess: () => void
  ): void {
    if (selectedSubstitution === -1) return;

    const existing = mapping.substitutions[selectedSubstitution];
    const { sourceExpression, targetExpression, pathSource, pathTarget } = substitutionModel;
    const updatedSubstitution: Substitution = (sourceExpression.valid && targetExpression.valid)
      ? { ...existing, pathSource, pathTarget }
      : { ...existing };

    const duplicateIndex = mapping.substitutions.findIndex(
      (sub, index) => index !== selectedSubstitution && sub.pathTarget === updatedSubstitution.pathTarget
    );

    if (duplicateIndex === -1) {
      mapping.substitutions[selectedSubstitution] = updatedSubstitution;
      onSuccess();
      return;
    }

    // Assign before splicing so the edited value survives the index shift regardless of which
    // of the two indices is larger.
    mapping.substitutions[duplicateIndex] = updatedSubstitution;
    mapping.substitutions.splice(selectedSubstitution, 1);
    this.alertService.info(
      `Merged into substitution # ${duplicateIndex + 1}: it already targeted [${updatedSubstitution.pathTarget}].`
    );
    onSuccess();
  }

  deleteSubstitution(selected: number, mapping: Mapping, onSuccess: () => void): void {
    if (selected < mapping.substitutions.length) {
      mapping.substitutions.splice(selected, 1);
      onSuccess();
    }
  }
}