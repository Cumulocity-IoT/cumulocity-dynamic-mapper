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
import { BehaviorSubject, Subject } from 'rxjs';
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

  constructor(private fb: FormBuilder) {
  }

  ngOnInit(): void {
    this.labels = {
      ok: this.isDuplicate ? 'Overwrite' : 'Save',
      cancel: 'Cancel'
    };
    this.createForm();

    this.editedSubstitution = this.substitution;
    const isReadOnly = this.stepperConfiguration.editorMode == EditorMode.READ_ONLY;
    this.repairStrategyOptions = Object.keys(RepairStrategy)
      .filter((key) => key != 'IGNORE')
      .map((key) => {
        const isArrayOnlyStrategy = key == 'USE_FIRST_VALUE_OF_ARRAY' || key == 'USE_LAST_VALUE_OF_ARRAY';
        const requiresArrayButNotExpanding = isArrayOnlyStrategy && !this.substitution.expandArray;
        return {
          label: key,
          value: key,
          disabled: isReadOnly || requiresArrayButNotExpanding
        };
      });

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

  isExpandToArrayDisabled() {
    const d0 = this.stepperConfiguration.editorMode == EditorMode.READ_ONLY;
    const d1 = this.mapping.direction == Direction.OUTBOUND;
    const r = d0 || d1;
    return r;
  }

  isRepairStrategyDisabled() {
    const r =
      this.stepperConfiguration.editorMode == EditorMode.READ_ONLY ||
      this.mapping.direction == Direction.OUTBOUND;
    return r;
  }

  ngOnDestroy(): void {
    this.disabled$.complete();
  }
}
