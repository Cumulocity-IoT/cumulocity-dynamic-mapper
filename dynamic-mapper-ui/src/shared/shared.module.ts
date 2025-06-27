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
import { RouterModule } from '@angular/router';
import { CoreModule } from '@c8y/ngx-components';
import { FORMLY_CONFIG } from '@ngx-formly/core';
import { BsDatepickerModule } from 'ngx-bootstrap/datepicker';
import { BsDropdownModule } from 'ngx-bootstrap/dropdown';
import { PaginationModule } from 'ngx-bootstrap/pagination';
import { WrapperCustomFormField } from './component/formly/custom-form-field-wrapper.component';
import { FieldInputCustom } from './component/formly/input-custom.type.component';
import { FieldTextareaCustom } from './component/formly/textarea.type.component';
import { ConfirmationModalComponent } from './confirmation/confirmation-modal.component';
import { ConnectorGridComponent } from './connector-configuration/connector-grid.component';
import { ConnectorConfigurationModalComponent } from './connector-configuration/edit/connector-configuration-modal.component';
import { CheckedRendererComponent } from './connector-configuration/renderer/checked-renderer.component';
import { ConnectorDetailCellRendererComponent } from './connector-configuration/renderer/connector-link.renderer.component';
import { ConnectorStatusRendererComponent } from './connector-configuration/renderer/connector-status.renderer.component';
import { ConnectorStatusEnabledRendererComponent } from './connector-configuration/renderer/status-enabled-renderer.component';
import { ConnectorDetailsComponent } from './connector-details/connector-details.component';
import { ConnectorStatusComponent } from './connector-log/connector-log.component';
import { JsonEditorComponent } from './component/editor/jsoneditor.component';
import { CamelCasePipe } from './misc/camel-case.pipe';
import { CapitalizeCasePipe } from './misc/capitalize-case.pipe';
import { FilterJsonPipe } from './misc/filter-json.pipe';
import { FormatStringPipe } from './misc/format-string.pipe';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { Base64DecodePipe } from './misc/base64-decode.pipe';

@NgModule({
  declarations: [
    CheckedRendererComponent,
    JsonEditorComponent,
    ConfirmationModalComponent,
    CamelCasePipe,
    FilterJsonPipe,
    Base64DecodePipe,
    CapitalizeCasePipe,
    FormatStringPipe,
    ConnectorStatusComponent,
    ConnectorGridComponent,
    ConnectorDetailsComponent,
    ConnectorConfigurationModalComponent,
    ConnectorStatusEnabledRendererComponent,
    ConnectorStatusRendererComponent,
    ConnectorDetailCellRendererComponent,
    WrapperCustomFormField,
    FieldTextareaCustom,
    FieldInputCustom,
  ],
  imports: [
    CoreModule,
    BsDatepickerModule,
    PaginationModule,
    PopoverModule,
    RouterModule,
    BsDropdownModule.forRoot()
  ],
  exports: [
    JsonEditorComponent,
    ConfirmationModalComponent,
    CamelCasePipe,
    FilterJsonPipe,
    Base64DecodePipe,
    CapitalizeCasePipe,
    FormatStringPipe,
    ConnectorStatusComponent,
    ConnectorGridComponent,
    ConnectorDetailsComponent,
    ConnectorConfigurationModalComponent,
    WrapperCustomFormField,
    FieldTextareaCustom,
    FieldInputCustom,
  ],
  providers:[FormatStringPipe,
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
          { name: 'boolean', extends: 'checkbox' }
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
export class SharedModule {}