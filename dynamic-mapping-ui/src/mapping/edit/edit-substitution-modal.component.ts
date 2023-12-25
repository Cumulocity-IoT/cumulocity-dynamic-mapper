import { Component, Input, OnDestroy, OnInit } from '@angular/core';
import { ModalLabels } from '@c8y/ngx-components';
import { BehaviorSubject, Subject } from 'rxjs';
import {
  Direction,
  Mapping,
  MappingSubstitution,
  RepairStrategy
} from '../../shared';
import { EditorMode, StepperConfiguration } from '../step-main/stepper-model';
import { definesDeviceIdentifier } from '../shared/util';

@Component({
  selector: 'd11r-edit-substitution-modal',
  templateUrl: './edit-substitution-modal.component.html'
})
export class EditSubstitutionComponent implements OnInit, OnDestroy {
  closeSubject: Subject<MappingSubstitution> = new Subject();
  labels: ModalLabels = { ok: 'Save', cancel: 'Dismiss' };
  @Input() substitution: MappingSubstitution;
  @Input() duplicate: boolean;
  @Input() existingSubstitution: number;
  @Input() stepperConfiguration: StepperConfiguration;
  @Input() mapping: Mapping;
  override: boolean = false;
  repairStrategyOptions: any[];
  substitutionText: string;
  editedSubstitution: MappingSubstitution;
  disabled$: BehaviorSubject<boolean> = new BehaviorSubject(false);

  ngOnInit(): void {
    this.editedSubstitution = this.substitution;
    this.repairStrategyOptions = Object.keys(RepairStrategy)
      .filter((key) => key != 'IGNORE' && key != 'CREATE_IF_MISSING')
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
      this.mapping.targetAPI,
      this.substitution,
      this.stepperConfiguration.direction
    )
      ? '* '
      : '';
    this.substitutionText = `[ ${marksDeviceIdentifier}${this.substitution.pathSource} -> ${this.substitution.pathTarget} ]`;
    this.disabled$.next(this.duplicate);
    // console.log("Repair Options:", this.repairStrategyOptions);
    console.log('Existing substitution:', this.existingSubstitution);
  }

  onDismiss() {
    console.log('Dismiss');
    this.closeSubject.next(undefined);
  }

  onSave() {
    console.log('Save');
    this.closeSubject.next(this.editedSubstitution);
  }

  onOverrideChanged() {
    const result = this.duplicate && !this.override;
    console.log('Override:', result);
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

  isResolve2ExternalIdDisabled() {
    const r =
      this.stepperConfiguration.editorMode == EditorMode.READ_ONLY ||
      this.stepperConfiguration.direction == Direction.INBOUND;
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
