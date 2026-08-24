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
import { BsModalService } from 'ngx-bootstrap/modal';
import { filter, take } from 'rxjs/operators';
import { Substitution, Mapping, StepperConfiguration } from '../../shared';
import { SubstitutionModel } from '../shared/stepper.model';
import { EditSubstitutionComponent } from '../substitution/edit/edit-substitution-modal.component';

@Injectable()
export class SubstitutionManagementService {
  private bsModalService = inject(BsModalService);

  isSubstitutionValid(substitutionModel: SubstitutionModel): boolean {
    const { sourceExpression, targetExpression, pathSource, pathTarget } = substitutionModel;
    return sourceExpression?.valid &&
           targetExpression?.valid &&
           pathSource !== '' &&
           pathTarget !== '';
  }

  /**
   * Bulk-replaces all substitutions in one shot, without the per-item duplicate/expert-mode
   * confirmation modal `addSubstitution()` shows below — appropriate for programmatic
   * replacement (e.g. applying a freshly AI-generated set) where the caller already means to
   * replace the full set atomically. Looping `addSubstitution()` instead is unsafe here: it's
   * fire-and-forget (no return value to await), so if the generated set contains two entries
   * sharing a `pathTarget` — plausible for JSONata expressions — or expert mode is on, each
   * hit opens its own confirmation modal while the loop keeps running, stacking dialogs.
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

  addSubstitution(
    substitutionModel: SubstitutionModel,
    mapping: Mapping,
    stepperConfiguration: StepperConfiguration,
    expertMode: boolean,
    onSuccess: () => void
  ): void {
    const substitution: Substitution = this.toSubstitution(substitutionModel);
    const duplicateIndex = mapping.substitutions.findIndex(
      sub => sub.pathTarget === substitution.pathTarget
    );

    const isDuplicate = duplicateIndex !== -1;
    const duplicate = isDuplicate ? mapping.substitutions[duplicateIndex] : undefined;

    if (!expertMode && !isDuplicate) {
      mapping.substitutions.push(substitution);
      onSuccess();
      return;
    }

    const initialState = {
      isDuplicate,
      duplicate,
      duplicateSubstitutionIndex: duplicateIndex,
      substitution,
      mapping,
      stepperConfiguration
    };

    const modalRef = this.bsModalService.show(EditSubstitutionComponent, { initialState });

    modalRef.content.closeSubject
      .pipe(take(1))
      .subscribe((updatedSubstitution: Substitution) => {
        if (!updatedSubstitution) return;

        if (isDuplicate) {
          mapping.substitutions[duplicateIndex] = updatedSubstitution;
        } else {
          mapping.substitutions.push(updatedSubstitution);
        }

        onSuccess();
      });
  }

  updateSubstitution(
    selectedSubstitution: number,
    substitutionModel: SubstitutionModel,
    mapping: Mapping,
    stepperConfiguration: StepperConfiguration,
    onSuccess: () => void
  ): void {
    if (selectedSubstitution === -1) return;

    const initialState = {
      substitution: { ...mapping.substitutions[selectedSubstitution] },
      mapping,
      stepperConfiguration,
      isUpdate: true
    };

    const { sourceExpression, targetExpression, pathSource, pathTarget } = substitutionModel;
    if (sourceExpression.valid && targetExpression.valid) {
      initialState.substitution = {
        ...initialState.substitution,
        pathSource,
        pathTarget
      };
    }

    const modalRef = this.bsModalService.show(EditSubstitutionComponent, { initialState });

    modalRef.content.closeSubject
      .pipe(
        take(1),
        filter(Boolean)
      )
      .subscribe({
        next: (editedSubstitution: Substitution) => {
          try {
            mapping.substitutions[selectedSubstitution] = editedSubstitution;
            onSuccess();
          } catch (error) {
            console.log('Failed to update substitution', error);
          }
        },
        error: (error) => console.log('Error in modal operation', error)
      });
  }

  deleteSubstitution(selected: number, mapping: Mapping, onSuccess: () => void): void {
    if (selected < mapping.substitutions.length) {
      mapping.substitutions.splice(selected, 1);
      onSuccess();
    }
  }
}