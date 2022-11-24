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
import { CommonModule } from '@angular/common';
import { NgModule } from '@angular/core';
import { Route, RouterModule as NgRouterModule } from '@angular/router';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import {
  CoreModule,
  HOOK_NAVIGATOR_NODES,
  HOOK_TABS,
  HOOK_WIZARD,
  RouterModule
} from '@c8y/ngx-components';
import { ConfigurationModule } from './mqtt-configuration/configuration.module';
import { AddExtensionWizardComponent } from './mqtt-extension/add-extension-wizard.component';
import { ExtensionModule } from './mqtt-extension/extension.module';
import { MappingTreeModule } from './mqtt-mapping-tree/tree.module';
import { MappingTypeComponent } from './mqtt-mapping/mapping-type/mapping-type.component';
import { MappingModule } from './mqtt-mapping/mapping.module';
import { MonitoringModule } from './mqtt-monitoring/monitoring.module';
import { TestingModule } from './mqtt-testing-devices/testing.module';
import { MappingNavigationFactory } from './navigation.factory';
import { ServiceMappingComponent } from './service-mapping.component';
import { OverviewGuard } from './shared/overview.guard';
import { MappingTabFactory } from './tab.factory';
import { ExtensionComponent } from './mqtt-extension/extension.component';
import { ExtensionPropertiesComponent } from './mqtt-extension/extension-properties.component';

const extensionRoutes: Route[] = [
  {
    path: 'mqtt-mapping/extensions',
    component: ExtensionComponent,
    pathMatch: "full",
    children: [
      {
        // path: 'mqtt-mapping/extensions/properties/50051686',
        path: 'properties/:id',
        component: ExtensionPropertiesComponent,
      }
    ]
    //canActivate: [ExtensionGuard],
  },
  // {
  //   path: 'mqtt-mapping/extensions/properties/:id',
  //   component: ExtensionPropertiesComponent,
  // }
];


@NgModule({
  imports: [
    CoreModule,
    CommonModule,
    TestingModule,
    MappingModule,
    MappingTreeModule,
    MonitoringModule,
    ConfigurationModule,
    ExtensionModule,
    FormsModule,
    ReactiveFormsModule,
  ],
  exports: [
    ServiceMappingComponent,
  ],
  entryComponents: [ServiceMappingComponent],
  declarations: [
    ServiceMappingComponent
  ],
  providers: [
    OverviewGuard,
    { provide: HOOK_NAVIGATOR_NODES, useClass: MappingNavigationFactory, multi: true },
    { provide: HOOK_TABS, useClass: MappingTabFactory, multi: true },
    {
      provide: HOOK_WIZARD,
      useValue: {
        // The id of a wizard to which the entry should be hooked.
        wizardId: 'addMappingWizard',
        // The container component is responsible for handling subsequent steps in the wizard.
        component: MappingTypeComponent,
        // Menu entry name
        name: 'App mapping',
        // Menu entry icon
        c8yIcon: 'plus-circle'
      },
      multi: true
    },
    {
      provide: HOOK_WIZARD,
      useValue: {
        wizardId: 'uploadExtensionWizard',
        component: AddExtensionWizardComponent,
        name: 'Upload Extension',
        c8yIcon: 'upload'
      },
      multi: true
    },
  ],
})
export class MQTTMappingModule {
  constructor() { }
}