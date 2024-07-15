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
  CoreModule,
  DynamicFormsModule,
  hookRoute,
  ModalModule
} from '@c8y/ngx-components';
import { AssetSelectorModule } from '@c8y/ngx-components/assets-navigator';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { BrokerConfigurationModule } from '../configuration';
import { SharedModule } from '../shared';
import { EditSubstitutionComponent } from './edit/edit-substitution-modal.component';
import { MappingComponent } from './grid/mapping.component';
import { ImportMappingsComponent } from './import-modal/import.component';
import { MappingTypeComponent } from './mapping-type/mapping-type.component';
import { OverwriteSubstitutionModalComponent } from './overwrite/overwrite-substitution-modal.component';
import { APIRendererComponent } from './renderer/api.renderer.component';
import { NameRendererComponent } from './renderer/name.renderer.component';
import { QOSRendererComponent } from './renderer/qos-cell.renderer.component';
import { SnoopedTemplateRendererComponent } from './renderer/snoopedTemplate.renderer.component';
import { StatusActivationRendererComponent } from './renderer/status-activation-renderer.component';
import { StatusRendererComponent } from './renderer/status-cell.renderer.component';
import { TemplateRendererComponent } from './renderer/template.renderer.component';
import { WrapperFormlyHorizontal } from './shared/formly/horizontal.wrapper.component';
import { FieldInputCustom } from './shared/formly/input-custom.type.component';
import { MessageField } from './shared/formly/message.type.component';
import { MappingStepperComponent } from './stepper-mapping/mapping-stepper.component';
import { SubstitutionRendererComponent } from './stepper-mapping/substitution/substitution-renderer.component';
import { MappingStepPropertiesComponent } from './step-one/mapping-properties.component';
import { MappingStepTestingComponent } from './step-three/mapping-testing.component';
import { MappingSubscriptionComponent } from './subscription/mapping-subscription.component';
import { WrapperCustomFormField } from './shared/formly/custom-form-field.wrapper.component';
import { MappingDeploymentRendererComponent } from './renderer/mappingDeployment.renderer.component';
import { SnoopingStepperComponent } from './stepper-snooping/snooping-stepper.component';
import { MappingConnectorComponent } from './step-connector/mapping-connector.component';

@NgModule({
  declarations: [
    MappingComponent,
    MappingStepperComponent,
    SnoopingStepperComponent,
    MappingStepTestingComponent,
    MappingStepPropertiesComponent,
    MappingSubscriptionComponent,
    OverwriteSubstitutionModalComponent,
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
	MappingConnectorComponent
  ],
  imports: [
    CoreModule,
    AssetSelectorModule,
    PopoverModule,
    DynamicFormsModule,
    ModalModule,
    SharedModule,
    BrokerConfigurationModule,
  ],
  exports: [],
  providers: [
    hookRoute({
      path: 'sag-ps-pkg-dynamic-mapping/node1/mappings/inbound',
      component: MappingComponent
    }),
    hookRoute({
      path: 'sag-ps-pkg-dynamic-mapping/node1/mappings/outbound',
      component: MappingComponent
    }),
  ]
})
export class MappingModule {}

