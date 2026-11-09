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

import { Injectable } from '@angular/core';
import { Substitution } from '../../shared';
import { SubstitutionModel } from '../../shared/mapping/stepper.model';

/** The substitution list after a change, plus a message for the user when the change had a side effect. */
export interface SubstitutionsUpdate {
  substitutions: Substitution[];
  notice?: string;
}

/**
 * Substitution list operations. Pure: every method takes the current list and returns a new one,
 * and none touches the mapping. The caller emits the result and the host that owns the mapping
 * applies it, so there is a single writer of `mapping.substitutions`.
 */
@Injectable()
export class SubstitutionManagementService {
  isSubstitutionValid(substitutionModel: SubstitutionModel): boolean {
    const { sourceExpression, targetExpression, pathSource, pathTarget } = substitutionModel;
    return sourceExpression?.valid &&
           targetExpression?.valid &&
           pathSource !== '' &&
           pathTarget !== '';
  }

  /**
   * Replaces the whole list, for programmatic replacement (e.g. applying a freshly AI-generated
   * set) where the caller means to replace the full set atomically, without the per-item
   * duplicate handling (and its notice) that `addSubstitution()` applies.
   */
  replaceAllSubstitutions(substitutionModels: SubstitutionModel[]): Substitution[] {
    return substitutionModels.map(model => this.toSubstitution(model));
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
   * defined is what the user just asked for. The substitution grid shows the full set inline, so
   * the replaced row is visible right there and a notice is enough to flag what happened.
   */
  addSubstitution(substitutionModel: SubstitutionModel, current: readonly Substitution[]): SubstitutionsUpdate {
    const substitution = this.toSubstitution(substitutionModel);
    const duplicateIndex = current.findIndex(sub => sub.pathTarget === substitution.pathTarget);

    if (duplicateIndex === -1) {
      return { substitutions: [...current, substitution] };
    }
    const substitutions = [...current];
    substitutions[duplicateIndex] = substitution;
    return {
      substitutions,
      notice: `Replaced substitution # ${duplicateIndex + 1}: it already targeted [${substitution.pathTarget}].`
    };
  }

  /**
   * Re-pointing pathSource/pathTarget can collide with a *different* substitution's pathTarget.
   * As in addSubstitution(), the two collapse into a single entry at the colliding index rather
   * than leaving two substitutions writing the same target, and a notice reports it.
   *
   * @returns `undefined` when nothing is selected (index -1 or out of range)
   */
  updateSubstitution(
    selectedSubstitution: number,
    substitutionModel: SubstitutionModel,
    current: readonly Substitution[]
  ): SubstitutionsUpdate | undefined {
    if (selectedSubstitution < 0 || selectedSubstitution >= current.length) return undefined;

    const existing = current[selectedSubstitution];
    const { sourceExpression, targetExpression, pathSource, pathTarget } = substitutionModel;
    const updated: Substitution = (sourceExpression.valid && targetExpression.valid)
      ? { ...existing, pathSource, pathTarget }
      : { ...existing };

    const duplicateIndex = current.findIndex(
      (sub, index) => index !== selectedSubstitution && sub.pathTarget === updated.pathTarget
    );

    const substitutions = [...current];
    if (duplicateIndex === -1) {
      substitutions[selectedSubstitution] = updated;
      return { substitutions };
    }

    // Assign before splicing so the edited value survives the index shift regardless of which
    // of the two indices is larger.
    substitutions[duplicateIndex] = updated;
    substitutions.splice(selectedSubstitution, 1);
    return {
      substitutions,
      notice: `Merged into substitution # ${duplicateIndex + 1}: it already targeted [${updated.pathTarget}].`
    };
  }

  /** @returns the list without the substitution at `selected`, or `undefined` when out of range */
  deleteSubstitution(selected: number, current: readonly Substitution[]): Substitution[] | undefined {
    if (selected < 0 || selected >= current.length) return undefined;
    return current.filter((_, index) => index !== selected);
  }

  /** In-grid edit of a single substitution (expandArray / repairStrategy). */
  patchSubstitution(
    current: readonly Substitution[],
    index: number,
    patch: Partial<Substitution>
  ): Substitution[] {
    return current.map((sub, i) => (i === index ? { ...sub, ...patch } : sub));
  }
}
