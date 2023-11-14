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
import { FormsModule, ReactiveFormsModule } from "@angular/forms";
import { Route } from "@angular/router";
import { CoreModule, hookNavigator, hookTab } from "@c8y/ngx-components";
import { BsModalService, ModalModule } from "ngx-bootstrap/modal";
import { BrokerConfigurationModule } from "./configuration/broker-configuration.module";
import { ExtensionModule } from "./extension/extension.module";
import { ExtensionComponent } from "./extension/grid/extension.component";
import { ExtensionPropertiesComponent } from "./extension/properties/extension-properties.component";
import { MappingTreeModule } from "./mapping-tree/tree.module";
import { MappingModule } from "./mapping/mapping.module";
import { MonitoringModule } from "./monitoring/monitoring.module";
import { MappingNavigationFactory, MappingTabFactory, OverviewGuard } from "./shared";
import { TestingModule } from "./testing-devices/testing.module";

const extensionRoutes: Route[] = [
  {
    path: "sag-ps-pkg-dynamic-mapping/extensions",
    component: ExtensionComponent,
    pathMatch: "full",
    children: [
      {
        path: "properties/:id",
        component: ExtensionPropertiesComponent,
      },
    ],
    //canActivate: [ExtensionGuard],
  },
];

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
  ],
  exports: [],
  entryComponents: [],
  declarations: [],
  providers: [
    OverviewGuard,
    BsModalService,
    hookNavigator(MappingNavigationFactory),
    hookTab(MappingTabFactory),
  ],
})
export class DynamicMappingModule {
  constructor() {}
}
