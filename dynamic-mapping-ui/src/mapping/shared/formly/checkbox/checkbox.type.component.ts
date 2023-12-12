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