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
import { Component, Input, OnDestroy, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, FormsModule } from '@angular/forms';
import { CoreModule, ModalLabels } from '@c8y/ngx-components';
import { BehaviorSubject, Subject, takeUntil } from 'rxjs';
import {
  Direction,
  Mapping,
  Substitution,
  RepairStrategy,
  SharedModule
} from '../../../shared';
import { definesDeviceIdentifier, StepperConfiguration } from '../../../shared/mapping/mapping.model';
import { EditorMode } from '../../shared/stepper.model';
import { PopoverModule } from 'ngx-bootstrap/popover';

@Component({
  selector: 'd11r-edit-substitution-modal',
  templateUrl: './edit-substitution-modal.component.html',
  imports:[CoreModule, SharedModule, PopoverModule, FormsModule],
  standalone: true
})
export class EditSubstitutionComponent implements OnInit, OnDestroy {
  @Input() substitution: Substitution;
  @Input() duplicate: Substitution;
  @Input() isDuplicate: boolean;
  @Input() isUpdate: boolean = false;
  @Input() duplicateSubstitutionIndex: number;
  @Input() stepperConfiguration: StepperConfiguration;
  @Input() mapping: Mapping;

  substitutionForm: FormGroup;
  closeSubject: Subject<Substitution> = new Subject();
  labels: ModalLabels;
  override: boolean = false;
  repairStrategyOptions: { label: string; value: string; disabled: boolean }[];
  substitutionText: string;
  editedSubstitution: Substitution;
  disabled$: BehaviorSubject<boolean> = new BehaviorSubject(false);
  Direction = Direction;
  private readonly destroy$ = new Subject<void>();

  constructor(private fb: FormBuilder) {
  }

  ngOnInit(): void {
    this.labels = {
      ok: this.isDuplicate ? 'Overwrite' : 'Save',
      cancel: 'Cancel'
    };
    this.createForm();

    this.editedSubstitution = this.substitution;
    this.updateRepairStrategyOptions(this.substitution.expandArray);
    // Array-only strategies (USE_FIRST/LAST_VALUE_OF_ARRAY) become meaningless/meaningful again
    // as the user toggles "Expand as array" within this same modal session — recompute live.
    this.substitutionForm.get('expandArray')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(expandArray => this.updateRepairStrategyOptions(expandArray));

    const marksDeviceIdentifier = definesDeviceIdentifier(
      this.mapping,
      this.substitution,
    )
      ? '* '
      : '';
    if (this.isDuplicate)
      this.substitutionText = `[ ${marksDeviceIdentifier}${this.duplicate.pathSource} -> ${this.duplicate.pathTarget} ]`;
    this.disabled$.next(this.isDuplicate);

    this.substitutionForm.patchValue({
      pathSource: this.editedSubstitution.pathSource,
      pathTarget: this.editedSubstitution.pathTarget,
      substitution: `[ ${this.editedSubstitution.pathSource} -> ${this.editedSubstitution.pathTarget}]`,
      expandArray: this.editedSubstitution.expandArray,
      repairStrategy: this.editedSubstitution.repairStrategy
    });
  }

  private updateRepairStrategyOptions(expandArray: boolean): void {
    const isReadOnly = this.stepperConfiguration.editorMode == EditorMode.READ_ONLY;
    this.repairStrategyOptions = Object.keys(RepairStrategy)
      .map((key) => {
        const isArrayOnlyStrategy = key == 'USE_FIRST_VALUE_OF_ARRAY' || key == 'USE_LAST_VALUE_OF_ARRAY';
        // These strategies collapse an extracted array to a single element instead of expanding
        // it into multiple substitutions — meaningless once "Expand as array" already splits the
        // array into N outputs, so disable them exactly when expandArray is on (not off).
        const meaninglessWhileExpanding = isArrayOnlyStrategy && expandArray;
        return {
          label: key,
          value: key,
          disabled: isReadOnly || meaninglessWhileExpanding
        };
      });
  }

  createForm() {
    this.substitutionForm = this.fb.group({
      pathSource: [{ value: '', disabled: true }],
      pathTarget: [{ value: '', disabled: true }],
      substitution: [{ value: '', disabled: true }],
      expandArray: [{ value: false, disabled: this.isExpandToArrayDisabled() }],
      repairStrategy: [{ value: '', disabled: this.isRepairStrategyDisabled() }]
    });
  }

  onDismiss() {
    this.closeSubject.next(undefined);
  }

  onSave() {
    // A duplicate substitution may only be saved once the user has explicitly opted in via the
    // "Overwrite" toggle — disabled$ tracks that gate (see onOverrideChanged()).
    if (this.substitutionForm.valid && !this.disabled$.value) {
      // pathSource/pathTarget/substitution are read-only display fields, not editable here —
      // only expandArray/repairStrategy are real, user-editable Substitution properties. Read via
      // getRawValue() since repairStrategy may be a disabled control (see isRepairStrategyDisabled).
      const { expandArray, repairStrategy } = this.substitutionForm.getRawValue();
      this.editedSubstitution = {
        ...this.editedSubstitution,
        expandArray,
        repairStrategy
      };
      this.closeSubject.next(this.editedSubstitution);
    }
  }

  onOverrideChanged() {
    const result = this.isDuplicate && !this.override;
    this.disabled$.next(result);
  }

  /** OUTBOUND mappings always produce exactly one target message — there is no fan-out to
   *  expand into, so "Expand as array" has no effect and stays locked for that direction. */
  isExpandToArrayDisabled() {
    const isReadOnly = this.stepperConfiguration.editorMode == EditorMode.READ_ONLY;
    const isOutbound = this.mapping.direction == Direction.OUTBOUND;
    return isReadOnly || isOutbound;
  }

  /** Unlike expandArray, repairStrategy (CREATE_IF_MISSING, REMOVE_IF_MISSING_OR_NULL, IGNORE,
   *  USE_FIRST/LAST_VALUE_OF_ARRAY) is applied identically for both directions on the backend,
   *  so it must stay editable for OUTBOUND too — only READ_ONLY mode locks it. */
  isRepairStrategyDisabled() {
    return this.stepperConfiguration.editorMode == EditorMode.READ_ONLY;
  }

  ngOnDestroy(): void {
    this.disabled$.complete();
    this.destroy$.next();
    this.destroy$.complete();
  }
}
