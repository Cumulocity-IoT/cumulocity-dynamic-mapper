import { Component, ViewChild } from '@angular/core';
import { FieldWrapper } from '@ngx-formly/core';
import { FormlyForm } from '@ngx-formly/core';

@Component({
  selector: 'formly-tooltip-wrapper',
  template: `
      <div class="form-group">
        <div class="input-group">
            <label [attr.for]="id" class="col-form-label" *ngIf="to.label">
            {{ to.label }}
            <span class="hidden-xs hidden-sm">
              <ng-template #popExpandField>{{to.tooltip}}</ng-template>
              <button class="btn-clean text-primary" [popover]="popExpandField"
                placement="top" triggers="focus" type="button">
                <i c8yIcon="question-circle-o"></i>
              </button>
            </span>
          </label>
            </div>
            <ng-container #fieldComponent></ng-container>
            <div *ngIf="showError" class="col-sm-3 invalid-feedback d-block">
          <formly-validation-message [field]="field"></formly-validation-message>
         </div>
      </div>
  `,
})
export class TooltipWrapper extends FieldWrapper {
}