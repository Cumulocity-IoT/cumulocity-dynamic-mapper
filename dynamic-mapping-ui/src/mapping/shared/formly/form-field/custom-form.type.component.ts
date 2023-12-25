import { Component, OnInit } from '@angular/core';
import { FieldWrapper } from '@ngx-formly/core';

@Component({
  selector: 'd11r-wrapper-form-field',
  templateUrl: './custom-form.component.html'
  // changeDetection: ChangeDetectionStrategy.OnPush
})
export class WrapperCustomFormField extends FieldWrapper implements OnInit {
  maxHelpBlockLength = 64;
  showDescriptionAsPopup: boolean;

  ngOnInit() {
    this.showDescriptionAsPopup =
      this.field.type === 'radio' ||
      this.field.type === 'typeahead' ||
      (this.to.description &&
        this.to.description.length > this.maxHelpBlockLength);
  }
}
