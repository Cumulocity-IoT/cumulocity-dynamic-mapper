import { Component, Input, OnInit } from "@angular/core";
import { ModalLabels } from "@c8y/ngx-components";
import { BehaviorSubject, Subject } from "rxjs";
import {
  Direction,
  Mapping,
  MappingSubstitution,
  RepairStrategy,
} from "../../shared/mapping.model";
import { EditorMode, StepperConfiguration } from "../step-main/stepper-model";
import { definesDeviceIdentifier } from "../../shared/util";

@Component({
  selector: "my-modal",
  template: ` <c8y-modal
    title="Edit properties of substitution"
    (onClose)="onSave($event)"
    (onDismiss)="onDismiss($event)"
    [labels]="labels"
    [disabled]="disabled$ | async"
    [headerClasses]="'modal-header dialog-header'"
  >
    <div>
      <c8y-form-group *ngIf="duplicate">
        <div>
          <span>{{
            "You are about to overwrite the existing substitution: #" +
              existingSubstitution | translate
          }}</span>
        </div>
        <br />
        <!--   <div style = "text-align: center;"><span>{{ substitutionText }}</span></div> -->
        <div style="text-align: center">
          <pre>{{ substitutionText }}</pre>
        </div>
        <br />
        <div>
          <span>{{ "Do you want to proceed?" | translate }}</span>
        </div>
        <label class="c8y-switch">
          <input
            type="checkbox"
            [(ngModel)]="override"
            (change)="onOverrideChanged()"
          />
          <span></span>
          <span>
            {{ "Overwrite existing subscription" | translate }}
          </span>
        </label>
      </c8y-form-group>
      <c8y-form-group>
        <label>
          <span>
            {{ "Path source" | translate }}
          </span>
        </label>
        <input
          type="text"
          class="form-control"
          readOnly
          [(ngModel)]="editedSubstitution.pathSource"
        />
      </c8y-form-group>
      <c8y-form-group>
        <label>
          <span>
            {{ "Path target" | translate }}
          </span>
        </label>
        <input
          type="text"
          readOnly
          [(ngModel)]="editedSubstitution.pathTarget"
          class="form-control"
        />
      </c8y-form-group>
      <c8y-form-group>
        <label
          class="c8y-switch d-inline"
          title="Expand as array"
          style="padding-top: 6px"
        >
          <input
            type="checkbox"
            [(ngModel)]="editedSubstitution.expandArray"
            [disabled]="isExpandToArrayDisabled()"
          />
          <span></span>
          <span>
            {{ "Expand as array" | translate }}
          </span>
        </label>
        <div class="d-inline">
          <ng-template #popTemplateExpandAsArray>
            Current expression extracts an array. Consider to use the option
            &quot;Expand Array&quot; if you want to create multiple
            measurements, alarms, events or devices, i.e.
            &quot;multi-device&quot; or &quot;multi-value&quot;
          </ng-template>
          <button
            class="btn-clean text-primary"
            [popover]="popTemplateExpandAsArray"
            popoverTitle="Expand as array"
            placement="right"
            triggers="focus"
            type="button"
          >
            <i c8yIcon="question-circle-o"></i>
          </button>
        </div>
      </c8y-form-group>

      <c8y-form-group>
        <label
          class="c8y-switch d-inline"
          title="Resolve to externalId"
          style="padding-top: 6px"
        >
          <input
            type="checkbox"
            [(ngModel)]="editedSubstitution.resolve2ExternalId"
            [disabled]="isResolve2ExternalIdDisabled()"
          />
          <span></span>
          <span>
            {{ "Resolve to externalId" | translate }}
          </span>
        </label>
        <div class="d-inline">
          <ng-template #popTemplateResolve>
            Resolve system Cumulocity Id to externalId using externalIdType.
            This can onlybe used for OUTBOUND mappings.
          </ng-template>
          <button
            class="btn-clean text-primary"
            [popover]="popTemplateResolve"
            popoverTitle="Resolve to externalId"
            placement="right"
            triggers="focus"
            type="button"
          >
            <i c8yIcon="question-circle-o"></i>
          </button>
        </div>
      </c8y-form-group>
      <c8y-form-group>
        <label
          ><span>RepairStrategy</span>
          <span></span>
          <ng-template #popTemplateRepair>
            Strategy defining what should happen when extracted arrays in
            different expressions do not have the same size. How are missing
            values handled?
          </ng-template>
          <button
            class="btn-clean text-primary"
            [popover]="popTemplateRepair"
            popoverTitle="Repair Strategy"
            placement="right"
            triggers="focus"
            type="button"
          >
            <i c8yIcon="question-circle-o"></i></button
        ></label>
        <div class="c8y-select-wrapper">
          <select
            class="form-control"
            [(ngModel)]="editedSubstitution.repairStrategy"
            name="repairStrategy"
          >
            <option [value]="t.value" *ngFor="let t of repairStrategyOptions">
              {{ t.label }}
            </option>
          </select>
        </div>
      </c8y-form-group>
    </div>
  </c8y-modal>`,
})
export class EditSubstitutionComponent implements OnInit {
  closeSubject: Subject<MappingSubstitution> = new Subject();
  labels: ModalLabels = { ok: "Save", cancel: "Dismiss" };
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
      .filter((key) => key != "IGNORE" && key != "CREATE_IF_MISSING")
      .map((key) => {
        return {
          label: key,
          value: key,
          disabled:
            (!this.substitution.expandArray &&
              key != "DEFAULT" &&
              (key == "USE_FIRST_VALUE_OF_ARRAY" ||
                key == "USE_LAST_VALUE_OF_ARRAY")) ||
            this.stepperConfiguration.editorMode == EditorMode.READ_ONLY,
        };
      });

    let marksDeviceIdentifier = definesDeviceIdentifier(
      this.mapping.targetAPI,
      this.substitution,
      this.stepperConfiguration.direction
    )
      ? "* "
      : "";
    this.substitutionText = `[ ${marksDeviceIdentifier}${this.substitution.pathSource} -> ${this.substitution.pathTarget} ]`;
    this.disabled$.next(this.duplicate);
    //console.log("Repair Options:", this.repairStrategyOptions);
    console.log("Existing substitution:", this.existingSubstitution);
  }

  onDismiss(event) {
    console.log("Dismiss");
    this.closeSubject.next(undefined);
  }

  onSave(event) {
    console.log("Save");
    this.closeSubject.next(this.editedSubstitution);
  }

  onOverrideChanged() {
    let result = this.duplicate && !this.override;
    console.log("Override:", result);
    this.disabled$.next(result);
  }

  isExpandToArrayDisabled() {
    const d0 = this.stepperConfiguration.editorMode == EditorMode.READ_ONLY;
    const d1 = this.mapping.direction == Direction.OUTBOUND;
    const d2 = definesDeviceIdentifier(
      this.mapping.targetAPI,
      this.substitution,
      this.mapping.direction
    );
    //const r = d0 || d1 || (!d1 && d2);
    const r = d0 || d1;
    //console.log("Evaluation", d0,d1,d2,d3, this.templateModel.currentSubstitution)
    return r;
  }

  isResolve2ExternalIdDisabled() {
    const r =
      this.stepperConfiguration.editorMode == EditorMode.READ_ONLY ||
      this.stepperConfiguration.direction == Direction.INBOUND;
    //console.log("Evaluation", d0,d1,d2,d3, this.templateModel.currentSubstitution)
    return r;
  }

  isRepairStrategyDisabled() {
    const r =
      this.stepperConfiguration.editorMode == EditorMode.READ_ONLY ||
      this.stepperConfiguration.direction == Direction.OUTBOUND;
    //console.log("Evaluation", d0,d1,d2,d3, this.templateModel.currentSubstitution)
    return r;
  }
}

// Showing modal:

// import { BsModalService } from "ngx-bootstrap/modal";
// import { ModalLabels } from "@c8y/ngx-components";

// constructor(
//  public bsModalService: BsModalService,
// ) {}

// showModal() {
//  const modalRef = this.bsModalService.show(MyModalComponent);
//  modalRef.content.closeSubject.subscribe(result => {
//    console.log('results:', result);
//  });
// }
