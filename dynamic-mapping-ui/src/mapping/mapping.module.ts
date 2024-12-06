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
import {
  CommonModule,
  CoreModule,
  DynamicFormsModule,
  hookRoute,
  ModalModule
} from '@c8y/ngx-components';
import { AssetSelectorModule } from '@c8y/ngx-components/assets-navigator';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { BrokerConfigurationModule } from '../configuration';
import { SharedModule } from '../shared';
import { EditSubstitutionComponent } from './substitution/edit/edit-substitution-modal.component';
import { MappingComponent } from './grid/mapping.component';
import { ImportMappingsComponent } from './import/import-modal.component';
import { MappingTypeComponent } from './mapping-type/mapping-type.component';
import { APIRendererComponent } from './renderer/api.renderer.component';
import { NameRendererComponent } from './renderer/name.renderer.component';
import { QOSRendererComponent } from './renderer/qos.renderer.component';
import { SnoopedTemplateRendererComponent } from './renderer/snooped-template.renderer.component';
import { StatusActivationRendererComponent } from './renderer/status-activation.renderer.component';
import { StatusRendererComponent } from './renderer/status.renderer.component';
import { TemplateRendererComponent } from './renderer/template.renderer.component';
import { WrapperFormlyHorizontal } from './shared/formly/horizontal.wrapper.component';
import { FieldInputCustom } from './shared/formly/input-custom.type.component';
import { MessageField } from './shared/formly/message.type.component';
import { MappingStepperComponent } from './stepper-mapping/mapping-stepper.component';
import { SubstitutionRendererComponent } from './substitution/substitution-grid.component';
import { MappingStepPropertiesComponent } from './step-property/mapping-properties.component';
import { MappingStepTestingComponent } from './step-testing/mapping-testing.component';
import { DeviceSelectorSubscriptionComponent } from './subscription-grid/device-selector/device-selector-subscription.component';
import { WrapperCustomFormField } from './shared/formly/custom-form-field.wrapper.component';
import { MappingDeploymentRendererComponent } from './renderer/mapping-deployment.renderer.component';
import { SnoopingStepperComponent } from './stepper-snooping/snooping-stepper.component';
import { MappingConnectorComponent } from './step-connector/mapping-connector.component';
import { FORMLY_CONFIG } from '@ngx-formly/core';
import { FieldTextareaCustom } from './shared/formly/textarea.type.component';
import {
  checkTopicsOutboundAreValid,
  checkTopicsInboundAreValid
} from './shared/util';
import { NODE1 } from '../shared/mapping/util';
import { MappingSubscriptionComponent } from './subscription-grid/subscription.component';
import { MappingIdCellRendererComponent } from './renderer/mapping-id.renderer.component';
import { SnoopExplorerComponent } from './snoop-explorer/snoop-explorer-modal.component';
import { AdviceActionComponent } from './grid/advisor/advice-action.component';
import { MappingFilterComponent } from './filter/mapping-filter.component';

@NgModule({
  declarations: [
    MappingComponent,
    MappingStepperComponent,
    SnoopingStepperComponent,
    MappingStepTestingComponent,
    MappingStepPropertiesComponent,
    DeviceSelectorSubscriptionComponent,
    EditSubstitutionComponent,
    ImportMappingsComponent,
    StatusRendererComponent,
    MappingDeploymentRendererComponent,
    QOSRendererComponent,
    TemplateRendererComponent,
    SnoopedTemplateRendererComponent,
    SubstitutionRendererComponent,
    StatusActivationRendererComponent,
    APIRendererComponent,
    NameRendererComponent,
    MappingTypeComponent,
    MessageField,
    WrapperFormlyHorizontal,
    WrapperCustomFormField,
    FieldInputCustom,
    MappingConnectorComponent,
    MappingSubscriptionComponent,
    DeviceSelectorSubscriptionComponent,
    MappingIdCellRendererComponent,
    SnoopExplorerComponent,
    AdviceActionComponent,
    MappingFilterComponent
  ],
  imports: [
    CoreModule,
    CommonModule,
    AssetSelectorModule,
    PopoverModule,
    DynamicFormsModule,
    ModalModule,
    SharedModule,
    BrokerConfigurationModule
  ],
  exports: [],
  providers: [
    hookRoute({
      path: `sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/inbound`,
      component: MappingComponent
    }),
    hookRoute({
      path: `sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/outbound`,
      component: MappingComponent
    }),
    hookRoute({
      path: `sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/outboundSubscription`,
      component: MappingSubscriptionComponent
    }),
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
          {
            name: 'message-field',
            component: MessageField
          }
        ],
        wrappers: [
          {
            name: 'custom-form-wrapper',
            component: WrapperCustomFormField
          }
        ],
        validators: [
          {
            name: 'checkTopicsInboundAreValid',
            validation: checkTopicsInboundAreValid
          },
          {
            name: 'checkTopicsOutboundAreValid',
            validation: checkTopicsOutboundAreValid
          }
        ]
      }
    }
  ]
})
export class MappingModule {}
