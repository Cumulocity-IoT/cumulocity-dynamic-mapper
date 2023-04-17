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
import { NgModule } from "@angular/core";
import { Route, RouterModule as NgRouterModule } from "@angular/router";
import { FormsModule, ReactiveFormsModule } from "@angular/forms";
import {
  CoreModule,
  HOOK_NAVIGATOR_NODES,
  HOOK_TABS,
} from "@c8y/ngx-components";
import { ConfigurationModule } from "./mqtt-configuration/configuration.module";
import { ExtensionModule } from "./mqtt-extension/extension.module";
import { MappingTreeModule } from "./mqtt-mapping-tree/tree.module";
import { MappingModule } from "./mqtt-mapping/mapping.module";
import { MonitoringModule } from "./mqtt-monitoring/monitoring.module";
import { TestingModule } from "./mqtt-testing-devices/testing.module";
import { MappingNavigationFactory } from "./navigation.factory";
import { ServiceMappingComponent } from "./service-mapping.component";
import { OverviewGuard } from "./shared/overview.guard";
import { MappingTabFactory } from "./tab.factory";
import { ExtensionComponent } from "./mqtt-extension/grid/extension.component";
import { ExtensionPropertiesComponent } from "./mqtt-extension/properties/extension-properties.component";
import { Editor2TestModule } from "./editor2/editor2-test.module";
import { BsModalService, ModalModule } from "ngx-bootstrap/modal";

const extensionRoutes: Route[] = [
  {
    path: "mqtt-mapping/extensions",
    component: ExtensionComponent,
    pathMatch: "full",
    children: [
      {
        // path: 'mqtt-mapping/extensions/properties/50051686',
        path: "properties/:id",
        component: ExtensionPropertiesComponent,
      },
    ],
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
    TestingModule,
    MappingModule,
    MappingTreeModule,
    MonitoringModule,
    ConfigurationModule,
    ExtensionModule,
    Editor2TestModule,
    FormsModule,
    ModalModule,
    ReactiveFormsModule,
  ],
  exports: [ServiceMappingComponent],
  entryComponents: [ServiceMappingComponent],
  declarations: [ServiceMappingComponent],
  providers: [
    OverviewGuard,
    BsModalService,
    {
      provide: HOOK_NAVIGATOR_NODES,
      useClass: MappingNavigationFactory,
      multi: true,
    },
    { provide: HOOK_TABS, useClass: MappingTabFactory, multi: true },
  ],
})
export class MQTTMappingModule {
  constructor() {}
}
