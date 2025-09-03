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
import { DeviceGridModule, DeviceGridService } from '@c8y/ngx-components/device-grid';
import { AssetSelectorModule } from '@c8y/ngx-components/assets-navigator';
import { EditorComponent, MonacoEditorMarkerValidatorDirective } from '@c8y/ngx-components/editor';
import { FORMLY_CONFIG } from '@ngx-formly/core';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { ServiceConfigurationModule } from '../configuration';
import { ManageTemplateComponent, LabelRendererComponent, NODE1, SharedModule, featureResolver } from '../shared';
import { MappingFilterComponent } from './filter/mapping-filter.component';
import { AdviceActionComponent } from './grid/advisor/advice-action.component';
import { MappingComponent } from './grid/mapping.component';
import { ImportMappingsComponent } from './import/import-modal.component';
import { MappingTypeDrawerComponent } from './mapping-create/mapping-type-drawer.component';
import { MappingDeploymentRendererComponent } from './renderer/mapping-deployment.renderer.component';
import { MappingIdCellRendererComponent } from './renderer/mapping-id.renderer.component';
import { NameRendererComponent } from './renderer/name.renderer.component';
import { QOSRendererComponent } from './renderer/qos.renderer.component';
import { SnoopedTemplateRendererComponent } from './renderer/snooped-template.renderer.component';
import { MappingStatusActivationRendererComponent } from './renderer/status-activation.renderer.component';
import { StatusRendererComponent } from './renderer/status.renderer.component';
import { TemplateRendererComponent } from './renderer/template.renderer.component';
import { checkTopicsInboundAreValid, checkTopicsOutboundAreValid } from './shared/util';
import { SnoopExplorerComponent } from './snoop-explorer/snoop-explorer-modal.component';
import { MappingConnectorComponent } from './step-connector/mapping-connector.component';
import { MappingStepPropertiesComponent } from './step-property/mapping-properties.component';
import { MappingStepTestingComponent } from './step-testing/mapping-testing.component';
import { MappingStepperComponent } from './stepper-mapping/mapping-stepper.component';
import { SnoopingStepperComponent } from './stepper-snooping/snooping-stepper.component';
import { DeviceSelectorSubscriptionComponent } from './subscription/device-selector/device-selector-subscription.component';
import { MappingSubscriptionComponent } from './subscription/subscription.component';
import { EditSubstitutionComponent } from './substitution/edit/edit-substitution-modal.component';
import { SubstitutionRendererComponent } from './substitution/substitution-grid.component';
import { DeviceSelectorSubscription2Component } from './subscription/device-selector2/device-selector-subscription2.component';
import { AIPromptComponent } from './prompt/ai-prompt.component';
import { DeviceSelectorSubscription3Component } from './subscription/device-selector3/device-selector-subscription3.component';
import { DeviceSelectorSubscription4Component } from './subscription/device-selector4/device-selector-subscription4.component';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { CollapseModule } from 'ngx-bootstrap/collapse';
import { DeviceClientMapComponent } from './client-relation/device-client-map.component';
import { ClientRelationStepperComponent } from './client-relation/client-relation-stepper.component';

@NgModule({
  declarations: [
    MappingComponent,
    MappingStepperComponent,
    SnoopingStepperComponent,
    ClientRelationStepperComponent,
    MappingStepTestingComponent,
    MappingStepPropertiesComponent,
    DeviceSelectorSubscriptionComponent,
    DeviceSelectorSubscription2Component,
    DeviceSelectorSubscription3Component,
    DeviceSelectorSubscription4Component,
    DeviceClientMapComponent,
    EditSubstitutionComponent,
    ImportMappingsComponent,
    StatusRendererComponent,
    MappingDeploymentRendererComponent,
    QOSRendererComponent,
    TemplateRendererComponent,
    SnoopedTemplateRendererComponent,
    SubstitutionRendererComponent,
    MappingStatusActivationRendererComponent,
    LabelRendererComponent,
    NameRendererComponent,
    MappingTypeDrawerComponent,
    MappingConnectorComponent,
    MappingSubscriptionComponent,
    MappingIdCellRendererComponent,
    SnoopExplorerComponent,
    AdviceActionComponent,
    MappingFilterComponent,
    ManageTemplateComponent,
    AIPromptComponent,
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
    DeviceGridModule,
    BrowserAnimationsModule,
    CollapseModule.forRoot(),
    MonacoEditorMarkerValidatorDirective
  ],
  exports: [],
  providers: [
    DeviceGridService,
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE1}/mappings/inbound`,
      component: MappingComponent,
      resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE1}/mappings/outbound`,
      component: MappingComponent,
      resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE1}/mappings/subscription/static`,
      component: MappingSubscriptionComponent,
      resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE1}/mappings/subscription/dynamic`,
      component: MappingSubscriptionComponent,
      resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE1}/mappings/relation/deviceToClientMap`,
      component: DeviceClientMapComponent,
      resolve: {
        feature: featureResolver
      }
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
