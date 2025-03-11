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
import {
  CommonModule,
  CoreModule,
  DynamicFormsModule,
  hookRoute,
  ModalModule
} from '@c8y/ngx-components';
import { EditorComponent, MonacoEditorMarkerValidatorDirective } from '@c8y/ngx-components/editor';
import { AssetSelectorModule } from '@c8y/ngx-components/assets-navigator';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { ServiceConfigurationModule } from '../configuration';
import { NODE1, LabelRendererComponent, SharedModule } from '../shared';
import { MappingComponent } from './grid/mapping.component';
import { ImportMappingsComponent } from './import/import-modal.component';
import { MappingTypeComponent } from './mapping-type/mapping-type.component';
import { MappingDeploymentRendererComponent } from './renderer/mapping-deployment.renderer.component';
import { NameRendererComponent } from './renderer/name.renderer.component';
import { QOSRendererComponent } from './renderer/qos.renderer.component';
import { SnoopedTemplateRendererComponent } from './renderer/snooped-template.renderer.component';
import { StatusActivationRendererComponent } from './renderer/status-activation.renderer.component';
import { StatusRendererComponent } from './renderer/status.renderer.component';
import { TemplateRendererComponent } from './renderer/template.renderer.component';
import { MappingConnectorComponent } from './step-connector/mapping-connector.component';
import { MappingStepPropertiesComponent } from './step-property/mapping-properties.component';
import { MappingStepTestingComponent } from './step-testing/mapping-testing.component';
import { MappingStepperComponent } from './stepper-mapping/mapping-stepper.component';
import { SnoopingStepperComponent } from './stepper-snooping/snooping-stepper.component';
import { DeviceSelectorSubscriptionComponent } from './subscription/device-selector/device-selector-subscription.component';
import { EditSubstitutionComponent } from './substitution/edit/edit-substitution-modal.component';
import { SubstitutionRendererComponent } from './substitution/substitution-grid.component';
import { FORMLY_CONFIG } from '@ngx-formly/core';
import { MappingFilterComponent } from './filter/mapping-filter.component';
import { AdviceActionComponent } from './grid/advisor/advice-action.component';
import { MappingIdCellRendererComponent } from './renderer/mapping-id.renderer.component';
import { checkTopicsInboundAreValid, checkTopicsOutboundAreValid } from './shared/util';
import { SnoopExplorerComponent } from './snoop-explorer/snoop-explorer-modal.component';
import { MappingSubscriptionComponent } from './subscription/subscription.component';

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
    LabelRendererComponent,
    NameRendererComponent,
    MappingTypeComponent,
    MappingConnectorComponent,
    MappingSubscriptionComponent,
    DeviceSelectorSubscriptionComponent,
    MappingIdCellRendererComponent,
    SnoopExplorerComponent,
    AdviceActionComponent,
    MappingFilterComponent,
  ],
  imports: [
    CoreModule,
    CommonModule,
    AssetSelectorModule,
    PopoverModule,
    DynamicFormsModule,
    ModalModule,
    SharedModule,
    ServiceConfigurationModule,
    EditorComponent,
    MonacoEditorMarkerValidatorDirective
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
      path: `sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/subscriptionOutbound`,
      component: MappingSubscriptionComponent
    }),
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

      }
    }
  ]
})
export class MappingModule { }
