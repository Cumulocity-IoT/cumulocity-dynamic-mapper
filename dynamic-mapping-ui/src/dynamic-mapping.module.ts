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
import { NgModule } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import {
  CommonModule,
  CoreModule,
  DynamicFormsModule,
  hookNavigator,
  hookTab
} from '@c8y/ngx-components';
import { BsModalService, ModalModule } from 'ngx-bootstrap/modal';
import { BrokerConfigurationModule } from './configuration/broker-configuration.module';
import { ExtensionModule } from './extension/extension.module';
import { MappingTreeModule } from './mapping-tree/tree.module';
import { MappingModule } from './mapping/mapping.module';
import { MonitoringModule } from './monitoring/monitoring.module';
import { FORMLY_CONFIG } from '@ngx-formly/core';
import { FormlyPresetModule } from '@ngx-formly/core/preset';

import {
  MappingNavigationFactory,
  MappingTabFactory,
  OverviewGuard,
  SharedModule
} from './shared';
import { TestingModule } from './testing-devices/testing.module';
import './shared/styles/shared.css';
import {
  checkTopicsInboundAreValid,
  checkTopicsOutboundAreValid
} from './mapping/shared/util';
import { SelectComponent } from './mapping/shared/formly/select/select.type.component';
import { FieldCheckbox } from './mapping/shared/formly/checkbox/checkbox.type.component';
import { FormlyFieldButton } from './mapping/shared/formly/button.type.component';
import { C8YSwitchField } from './mapping/shared/formly/c8y-switch.type.component';
import { FormlyFiller } from './mapping/shared/formly/filler.type.component';
import { WrapperCustomFormField } from './mapping/shared/formly/form-field/custom-form.type.component';
import { FormlyHorizontalWrapper } from './mapping/shared/formly/horizontal-wrapper.type.component';
import { FieldInputCustom } from './mapping/shared/formly/input-custom.type.component';
import { MessageField } from './mapping/shared/formly/message.type.component';
import { FormlyTextField } from './mapping/shared/formly/text.type.component';
import { FieldTextareaCustom } from './mapping/shared/formly/textarea.type.component';

@NgModule({
  imports: [
    CoreModule,
    TestingModule,
    MappingModule,
    MappingTreeModule,
    MonitoringModule,
    BrokerConfigurationModule,
    ExtensionModule,
    FormsModule,
    ModalModule,
    ReactiveFormsModule,
    DynamicFormsModule,
    CommonModule,
    DynamicFormsModule,
    FormlyPresetModule,
    SharedModule
  ],
  exports: [],
  declarations: [],
  providers: [
    OverviewGuard,
    BsModalService,
    hookNavigator(MappingNavigationFactory),
    hookTab(MappingTabFactory),
    {
      provide: FORMLY_CONFIG,
      multi: true,
      useValue: {
        validators: [
          {
            name: 'checkTopicsInboundAreValid',
            validation: checkTopicsInboundAreValid
          },
          {
            name: 'checkTopicsOutboundAreValid',
            validation: checkTopicsOutboundAreValid
          }
        ],
        types: [
          { name: 'text', component: FormlyTextField },
          { name: 'filler', component: FormlyFiller },
          { name: 'textarea-custom', component: FieldTextareaCustom },
          { name: 'input-custom', component: FieldInputCustom },
          { name: 'button', component: FormlyFieldButton },
          { name: 'message-field', component: MessageField },
          { name: 'c8y-switch', component: C8YSwitchField },
          {
            name: 'select',
            component: SelectComponent,
            wrappers: ['c8y-form-field']
          },
          { name: 'enum', extends: 'select' },
          { name: 'checkbox', component: FieldCheckbox },
          { name: 'boolean', extends: 'checkbox' }
        ],
        wrappers: [
          { name: 'form-field-horizontal', component: FormlyHorizontalWrapper },
          { name: 'custom-form-field', component: WrapperCustomFormField }
        ]
      }
    }
  ]
})
export class DynamicMappingModule {
  constructor() {}
}
