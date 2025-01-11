/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
import { FieldType } from '@ngx-formly/core';

@Component({
  selector: 'd11r-formly-field-textarea',
  template: `
    <textarea
       c8y-textarea-autoresize
      [class]="class"
      [readonly]="props.readonly"
      [required]="props.required"
      [formControl]="formControl"
      [cols]="props.cols"
      [rows]="props.rows"
      [formlyAttributes]="field"
      [placeholder]="props.placeholder"
    >
 {{ formControl.value }}
</textarea>
  `,
  styleUrls: ['./textarea.type.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class FieldTextareaCustom extends FieldType {
  get class() {
    return `form-control ${this.props['class']}`;
  }
  get readonly() {
    return this.props.readonly ? this.props.readonly : true;
  }
  get cols() {
    return this.props.cols ? 80 : this.props.cols;
  }
  get rows() {
    return this.props['v'] ? 4 : this.props.rows;
  }
}
