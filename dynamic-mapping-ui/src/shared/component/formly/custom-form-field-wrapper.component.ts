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
import { Component, OnInit } from '@angular/core';
import { FieldWrapper } from '@ngx-formly/core';

@Component({
  selector: 'd11r-wrapper-form-field',
  templateUrl: './custom-form-field-wrapper.component.html'
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
