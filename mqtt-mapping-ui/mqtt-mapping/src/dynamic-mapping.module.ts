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
import { APP_INITIALIZER, NgModule } from "@angular/core";
import { Route, RouterModule as NgRouterModule } from "@angular/router";
import { FormsModule, ReactiveFormsModule } from "@angular/forms";
import { CoreModule, hookNavigator, hookTab } from "@c8y/ngx-components";
import { ConfigurationModule } from "./mqtt-configuration/configuration.module";
import { ExtensionModule } from "./mqtt-extension/extension.module";
import { MappingTreeModule } from "./mqtt-mapping-tree/tree.module";
import { MappingModule } from "./mqtt-mapping/mapping.module";
import { MonitoringModule } from "./mqtt-monitoring/monitoring.module";
import { TestingModule } from "./mqtt-testing-devices/testing.module";
import { MappingNavigationFactory } from "./navigation.factory";
import { OverviewGuard } from "./shared/overview.guard";
import { MappingTabFactory } from "./tab.factory";
import { ExtensionComponent } from "./mqtt-extension/grid/extension.component";
import { ExtensionPropertiesComponent } from "./mqtt-extension/properties/extension-properties.component";
import { Editor2TestModule } from "./editor2/editor2-test.module";
import { BsModalService, ModalModule } from "ngx-bootstrap/modal";
import { BrokerConfigurationService } from "./mqtt-configuration/broker-configuration.service";

const extensionRoutes: Route[] = [
  {
    path: "sag-ps-pkg-mqtt-mapping/extensions",
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
    ConfigurationModule,
    ExtensionModule,
    FormsModule,
    ModalModule,
    ReactiveFormsModule,
  ],
  exports: [],
  entryComponents: [],
  declarations: [],
  providers: [
    {
      provide: APP_INITIALIZER,
      useFactory: initSynchronousFactory,
      multi: true,
    },
    OverviewGuard,
    BsModalService,
    hookNavigator(MappingNavigationFactory),
    hookTab(MappingTabFactory),
  ],
})
export class DynamicMappingModule {
  constructor() {}
}

export function initSynchronousFactory(
  brokerConfigurationService: BrokerConfigurationService,
) {
  return async () => {
    console.log('initServicesFactory - started');
    const features = await brokerConfigurationService.getFeatures();
    console.log('initServicesFactory - completed');
  };
}
