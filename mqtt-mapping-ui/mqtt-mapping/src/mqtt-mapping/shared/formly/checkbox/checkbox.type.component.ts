import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ConfigOption, FieldType } from '@ngx-formly/core';

@Component({
  selector: 'c8y-field-checkbox',
  templateUrl: './checkbox.type.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class FieldCheckbox extends FieldType {
  static readonly CONFIG: ConfigOption = {
    types: [
      {
        name: 'checkbox',
        component: FieldCheckbox
      },
      {
        name: 'boolean',
        extends: 'checkbox'
      },
      {
        name: 'switch',
        extends: 'checkbox',
        defaultOptions: {
          templateOptions: {
            switchMode: false,
            indeterminate: false
          }
        }
      }
    ]
  };

  defaultOptions = {
    templateOptions: {
      indeterminate: true,
      formCheck: 'custom' // 'custom' | 'custom-inline' | 'custom-switch' | 'stacked' | 'inline' | 'nolabel'
    }
  };
}
