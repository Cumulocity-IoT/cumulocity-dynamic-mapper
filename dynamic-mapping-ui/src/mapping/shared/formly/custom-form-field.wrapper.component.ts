import { Component, OnInit } from '@angular/core';
import { FieldWrapper } from '@ngx-formly/core';

@Component({
  selector: 'd11r-wrapper-form-field',
  templateUrl: './custom-form-field.wrapper.component.html'
  // changeDetection: ChangeDetectionStrategy.OnPush
})
export class WrapperCustomFormField extends FieldWrapper implements OnInit {
  maxHelpBlockLength = 64;
  showDescriptionAsPopup: boolean;
  customWrapperClass: string;
  classes: string;

  ngOnInit() {
    this.showDescriptionAsPopup =
      this.field.type === 'radio' ||
      this.field.type === 'typeahead' ||
      (this.props.description &&
        this.props.description.length > this.maxHelpBlockLength);
    // Get the custom class from the field's templateOptions
    this.customWrapperClass = this.field.props?.['customWrapperClass'] || '';
    this.classes = `form-group ${this.customWrapperClass}`;
  }
}
