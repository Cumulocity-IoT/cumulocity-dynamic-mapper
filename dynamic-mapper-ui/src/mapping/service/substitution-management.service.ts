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
import { Substitution, Mapping, StepperConfiguration, RepairStrategy } from '../../shared';
import { EditSubstitutionComponent } from '../substitution/edit/edit-substitution-modal.component';

@Injectable()
export class SubstitutionManagementService {
  private bsModalService = inject(BsModalService);

  isSubstitutionValid(substitutionModel: any): boolean {
    const { sourceExpression, targetExpression, pathSource, pathTarget } = substitutionModel;
    return sourceExpression?.valid && 
           targetExpression?.valid && 
           pathSource !== '' && 
           pathTarget !== '';
  }

  addSubstitution(
    substitutionModel: any,
    mapping: Mapping,
    stepperConfiguration: StepperConfiguration,
    expertMode: boolean,
    onSuccess: () => void
  ): void {
    const substitution = { ...substitutionModel };
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
    substitutionModel: any,
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