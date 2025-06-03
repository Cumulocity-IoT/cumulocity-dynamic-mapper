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
import { FormBuilder, FormGroup } from '@angular/forms';
import { ModalLabels } from '@c8y/ngx-components';
import { BehaviorSubject, Subject } from 'rxjs';
import {
  Direction,
  Mapping,
  MappingSubstitution,
  RepairStrategy
} from '../../../shared';
import { definesDeviceIdentifier, StepperConfiguration } from '../../../shared/mapping/mapping.model';
import { EditorMode } from '../../shared/stepper.model';

@Component({
  selector: 'd11r-edit-substitution-modal',
  templateUrl: './edit-substitution-modal.component.html',
  standalone: false
})
export class EditSubstitutionComponent implements OnInit, OnDestroy {
  @Input() substitution: MappingSubstitution;
  @Input() duplicate: MappingSubstitution;
  @Input() isDuplicate: boolean;
  @Input() isUpdate: boolean = false;
  @Input() duplicateSubstitutionIndex: number;
  @Input() stepperConfiguration: StepperConfiguration;
  @Input() mapping: Mapping;

  substitutionForm: FormGroup;
  closeSubject: Subject<MappingSubstitution> = new Subject();
  labels: ModalLabels;
  override: boolean = false;
  repairStrategyOptions: any[];
  substitutionText: string;
  editedSubstitution: MappingSubstitution;
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
    this.repairStrategyOptions = Object.keys(RepairStrategy)
      // .filter((key) => key != 'IGNORE' && key != 'CREATE_IF_MISSING')
      .filter((key) => key != 'IGNORE')
      .map((key) => {
        return {
          label: key,
          value: key,
          disabled:
            (!this.substitution.expandArray &&
              key != 'DEFAULT' &&
              (key == 'USE_FIRST_VALUE_OF_ARRAY' ||
                key == 'USE_LAST_VALUE_OF_ARRAY')) ||
            this.stepperConfiguration.editorMode == EditorMode.READ_ONLY
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
      expandArray: this.editedSubstitution.expandArray,
      repairStrategy: this.editedSubstitution.repairStrategy
    });
    // console.log("Repair Options:", this.repairStrategyOptions);
    // console.log('Existing substitution:', this.existingSubstitution);
  }

  createForm() {
    this.substitutionForm = this.fb.group({
      pathSource: [{ value: '', disabled: true }],
      pathTarget: [{ value: '', disabled: true }],
      expandArray: [{ value: false, disabled: this.isExpandToArrayDisabled() }],
      repairStrategy: ['']
    });
  }

  onDismiss() {
    // console.log('Dismiss');
    this.closeSubject.next(undefined);
  }

  onSave() {
    // console.log('Save');
    if (this.substitutionForm.valid) {
      const formValue = this.substitutionForm.value;
      // Update editedSubstitution with form values
      this.editedSubstitution = {
        ...this.editedSubstitution,
        ...formValue
      };
      this.closeSubject.next(this.editedSubstitution);
    }
  }

  onOverrideChanged() {
    const result = this.isDuplicate && !this.override;
    // console.log('Override:', result);
    this.disabled$.next(result);
  }

  isExpandToArrayDisabled() {
    const d0 = this.stepperConfiguration.editorMode == EditorMode.READ_ONLY;
    const d1 = this.mapping.direction == Direction.OUTBOUND;
    // const d2 = definesDeviceIdentifier(
    //   this.mapping.targetAPI,
    //   this.substitution,
    //   this.mapping.direction
    // );
    // const r = d0 || d1 || (!d1 && d2);
    const r = d0 || d1;
    // console.log("Evaluation", d0,d1,d2,d3, this.templateModel.currentSubstitution)
    return r;
  }

  isRepairStrategyDisabled() {
    const r =
      this.stepperConfiguration.editorMode == EditorMode.READ_ONLY ||
      this.stepperConfiguration.direction == Direction.OUTBOUND;
    // console.log("Evaluation", d0,d1,d2,d3, this.templateModel.currentSubstitution)
    return r;
  }

  ngOnDestroy(): void {
    this.disabled$.complete();
  }
}
