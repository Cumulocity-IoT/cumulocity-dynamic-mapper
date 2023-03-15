import { Component } from '@angular/core';
import { FieldType } from '@ngx-formly/core';

@Component({
 selector: 'formly-text',
 template: `{{ to.label }}`,
})
export class FormlyTextField extends FieldType {}