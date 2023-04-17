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
import { CoreModule, HOOK_ROUTE, Route } from "@c8y/ngx-components";
import { AddExtensionComponent } from "./extension-modal/add-extension.component";
import { AddExtensionWizardComponent } from "./add-extension-wizard.component";
import { ExtensionCardComponent } from "./extension-card/extension-card.component";
import { ExtensionComponent } from "./grid/extension.component";
import { BsDropdownModule } from "ngx-bootstrap/dropdown";
import { CollapseModule } from "ngx-bootstrap/collapse";
import { ExtensionPropertiesComponent } from "./properties/extension-properties.component";

const extensionRoutes: Route[] = [
  {
    path: "mqtt-mapping/extensions",
    children: [
      {
        path: "",
        pathMatch: "full",
        component: ExtensionComponent,
      },
      {
        path: "properties/:id",
        component: ExtensionPropertiesComponent,
      },
    ],
  },
];

@NgModule({
  declarations: [
    ExtensionComponent,
    ExtensionCardComponent,
    AddExtensionComponent,
    AddExtensionWizardComponent,
    ExtensionCardComponent,
    ExtensionPropertiesComponent,
  ],
  imports: [CoreModule, BsDropdownModule.forRoot(), CollapseModule.forRoot()],
  entryComponents: [
    AddExtensionComponent,
    ExtensionComponent,
    ExtensionCardComponent,
    ExtensionPropertiesComponent,
  ],
  exports: [],
  providers: [
    {
      provide: HOOK_ROUTE,
      useValue: extensionRoutes,
      multi: true,
    },
  ],
})
export class ExtensionModule {}
