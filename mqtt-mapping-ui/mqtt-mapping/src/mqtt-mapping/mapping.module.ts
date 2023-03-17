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
import { CoreModule, HOOK_ROUTE, Route } from '@c8y/ngx-components';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { MappingComponent } from './grid/mapping.component';
import { MappingTypeComponent } from './mapping-type/mapping-type.component';
import { OverwriteDeviceIdentifierModalComponent } from './overwrite/overwrite-device-identifier-modal.component';
import { OverwriteSubstitutionModalComponent } from './overwrite/overwrite-substitution-modal.component';
import { APIRendererComponent } from './renderer/api.renderer.component';
import { QOSRendererComponent } from './renderer/qos-cell.renderer.component';
import { SnoopedTemplateRendererComponent } from './renderer/snoopedTemplate.renderer.component';
import { StatusRendererComponent } from './renderer/status-cell.renderer.component';
import { ActiveRendererComponent } from './renderer/active.renderer.component';
import { TemplateRendererComponent } from './renderer/template.renderer.component';
import { SnoopingModalComponent } from './snooping/snooping-modal.component';
import { MappingStepperComponent } from './stepper/mapping-stepper.component';
import { SubstitutionRendererComponent } from './stepper/substitution/substitution-renderer.component';
import { SharedModule } from '../shared/shared.module';
import { ConfigurationModule } from '../mqtt-configuration/configuration.module';
import { AssetSelectorModule } from '@c8y/ngx-components/assets-navigator';
import { MappingSubscriptionComponent } from './subscription/mapping-subscription.component';
import { FormlyModule } from '@ngx-formly/core';
import { checkSubstitutionIsValid, checkTopicsInboundAreValid, checkTopicsOutboundAreValid } from '../shared/util';
import { FormlyTextField } from './shared/formly/text-field';
import { FormlyFieldButton } from './shared/formly/button-type';
import { MessageWrapper } from './shared/formly/message-wrapper';
import { MessageField } from './shared/formly/message-field';
import { FormlyHorizontalWrapper } from './shared/formly/horizontal-wrapper';
import { C8YSwitchField } from './shared/formly/c8y-switch-field';
import { CommonModule } from '@angular/common';
import { TooltipWrapper } from './shared/formly/tooltip-wrapper';


@NgModule({
  declarations: [
    MappingComponent,
    MappingStepperComponent,
    MappingSubscriptionComponent,
    OverwriteSubstitutionModalComponent,
    OverwriteDeviceIdentifierModalComponent,
    StatusRendererComponent,
    QOSRendererComponent,
    TemplateRendererComponent,
    SnoopedTemplateRendererComponent,
    SubstitutionRendererComponent,
    APIRendererComponent,
    ActiveRendererComponent,
    SnoopingModalComponent,
    MappingTypeComponent,
    MessageField,
    MessageWrapper,
    FormlyHorizontalWrapper,
    C8YSwitchField,
    TooltipWrapper
  ],
  imports: [
    CoreModule,
    AssetSelectorModule,
    SharedModule,
    PopoverModule,
    ConfigurationModule,
    FormlyModule.forRoot({
      validators: [
        { name: 'checkTopicsInboundAreValid', validation: checkTopicsInboundAreValid },
        { name: 'checkTopicsOutboundAreValid', validation: checkTopicsOutboundAreValid },
        { name: 'checkSubstitutionIsValid', validation: checkSubstitutionIsValid },
      ],
      types: [
        { name: 'text', component: FormlyTextField },
        { name: 'button', component: FormlyFieldButton },
        { name: 'message-field', component: MessageField },
        { name: 'c8y-switch', component: C8YSwitchField },
      ],
      wrappers: [
        { name: 'message-wrapper', component: MessageWrapper },
        { name: 'form-field-horizontal', component: FormlyHorizontalWrapper },
        { name: 'tooltip-wrapper', component: TooltipWrapper },
      ],
    }),
  ],
  entryComponents: [
    MappingComponent,
    OverwriteSubstitutionModalComponent,
    OverwriteDeviceIdentifierModalComponent,
    StatusRendererComponent,
    QOSRendererComponent,
    TemplateRendererComponent,
    SnoopedTemplateRendererComponent,
    SubstitutionRendererComponent,
    APIRendererComponent,
    ActiveRendererComponent,
    SnoopingModalComponent,
    MappingTypeComponent,
  ],
  exports: [],
  providers: [
    {
      provide: HOOK_ROUTE,
      useValue: [
        {
          path: 'mqtt-mapping/mappings/inbound',
          component: MappingComponent,
        },
      ] as Route[],
      multi: true,
    },
    {
      provide: HOOK_ROUTE,
      useValue: [
        {
          path: 'mqtt-mapping/mappings/outbound',
          component: MappingComponent,
        },
      ] as Route[],
      multi: true,
    },
  ]
})
export class MappingModule { }
