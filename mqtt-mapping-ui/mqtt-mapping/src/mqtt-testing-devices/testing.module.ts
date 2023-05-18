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
import { TestingComponent } from "./grid/testing.component";
import { TypeCellRendererComponent } from "./grid/type-data-grid-column/type.cell-renderer.component";
import { TypeFilteringFormRendererComponent } from "./grid/type-data-grid-column/type.filtering-form-renderer.component";
import { TypeHeaderCellRendererComponent } from "./grid/type-data-grid-column/type.header-cell-renderer.component";

@NgModule({
  declarations: [
    TestingComponent,
    TypeHeaderCellRendererComponent,
    TypeCellRendererComponent,
    TypeFilteringFormRendererComponent,
  ],
  imports: [CoreModule],
  entryComponents: [
    TypeHeaderCellRendererComponent,
    TypeCellRendererComponent,
    TypeFilteringFormRendererComponent,
  ],
  exports: [],
  providers: [
    {
      provide: HOOK_ROUTE,
      useValue: [
        {
          path: "sag-ps-pkg-mqtt-mapping/testing",
          component: TestingComponent,
        },
      ] as Route[],
      multi: true,
    },
  ],
})
export class TestingModule {}
