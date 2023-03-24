// panel-wrapper.component.ts
import { Component } from '@angular/core';
import { FieldType } from '@ngx-formly/core';

@Component({
  selector: 'formly-c8y-switch',
  template: `
    <div class="form-group row">
      <label class="c8y-switch" *ngIf="to.label">
        <input type="checkbox" [formControl]="formControl" [formlyAttributes]="field" />
        <span></span>
        {{ to.label }}
      </label>
      <div *ngIf="showError" class="col-sm-3 invalid-feedback d-block">
          <formly-validation-message [field]="field"></formly-validation-message>
      </div>
    </div>
  `,
})
export class C8YSwitchField extends FieldType {
}