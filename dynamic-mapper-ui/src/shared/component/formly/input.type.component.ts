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

import { ChangeDetectionStrategy, Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CoreModule } from '@c8y/ngx-components';
import { FieldType } from '@ngx-formly/core';

@Component({
  selector: 'd11r-formly-field-d11r-input',
  template: `@if (type !== 'number') {
  <input
    [type]="type"
    [formControl]="formControl"
    [class]="class"
    [formlyAttributes]="field"
    [required]="props.required"
    [attr.autocomplete]="props['autocomplete'] ? props['autocomplete'] : null"
    [class.is-invalid]="showError"
    />
} @else {
  <input
    type="number"
    [formControl]="formControl"
    [class]="class"
    [formlyAttributes]="field"
    [required]="props.required"
    [attr.autocomplete]="props['autocomplete']? props['autocomplete'] : null"
    [class.is-invalid]="showError"
    />
}
`,
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CoreModule,
    FormsModule,
  ]
})
export class CustomFieldInput extends FieldType {
  get type() {
    return this.props.type || 'text';
  }

  get class() {
    return `form-control ${this.props['class']}`;
  }
}
