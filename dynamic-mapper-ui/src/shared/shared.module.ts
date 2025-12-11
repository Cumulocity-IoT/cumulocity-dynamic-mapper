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
import { NgModule } from '@angular/core';
import { FORMLY_CONFIG } from '@ngx-formly/core';
import { WrapperCustomFormField } from './component/formly/custom-form-field-wrapper.component';
import { FieldInputCustom } from './component/formly/input-custom.type.component';
import { FieldTextareaCustom } from './component/formly/textarea.type.component';
import { FormatStringPipe } from './misc/format-string.pipe';
import { InputListFormlyComponent } from './component/formly/input-list-formly.component';
import { ConnectorConfigurationDrawerComponent } from './connector-configuration/edit/connector-configuration-drawer.component';
import { provideEcharts } from 'ngx-echarts';
import { CapitalizeCasePipe } from './misc/capitalize-case.pipe';
import { FilterJsonPipe } from './misc/filter-json.pipe';
import { Base64DecodePipe } from './misc/base64-decode.pipe';
import { CamelCasePipe } from './misc/camel-case.pipe';

@NgModule({
  imports: [
    ConnectorConfigurationDrawerComponent,
    CamelCasePipe,
    FormatStringPipe,
    CapitalizeCasePipe,
    Base64DecodePipe,
    FilterJsonPipe,
    FieldInputCustom,
    FieldTextareaCustom,
    WrapperCustomFormField,
    InputListFormlyComponent
  ],
  exports: [
    ConnectorConfigurationDrawerComponent,
    CamelCasePipe,
    FormatStringPipe,
    CapitalizeCasePipe,
    Base64DecodePipe,
    FilterJsonPipe,
    FieldInputCustom,
    FieldTextareaCustom,
    WrapperCustomFormField,
    InputListFormlyComponent
  ],
  providers: [CamelCasePipe, FormatStringPipe, CapitalizeCasePipe, Base64DecodePipe, FilterJsonPipe,
    provideEcharts(),
    {
      provide: FORMLY_CONFIG,
      multi: true,
      useValue: {
        types: [
          {
            name: 'textarea-custom',
            component: FieldTextareaCustom
          },
          {
            name: 'input-custom',
            component: FieldInputCustom
          },
          { name: 'enum', extends: 'select' },
          { name: 'boolean', extends: 'checkbox' },
          {
            name: 'd11r-input-list',
            component: InputListFormlyComponent // You'll need to create this wrapper component
          }
        ],
        wrappers: [
          {
            name: 'custom-form-field-wrapper',
            component: WrapperCustomFormField
          }
        ],
      }
    }
  ]
})
export class SharedModule { }