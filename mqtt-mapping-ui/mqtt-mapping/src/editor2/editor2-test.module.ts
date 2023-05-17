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
import { Editor2TestComponent } from "./editor2-test.component";
import { SharedModule } from "../shared/shared.module";

@NgModule({
  declarations: [Editor2TestComponent],
  imports: [CoreModule, SharedModule],
  entryComponents: [Editor2TestComponent],
  exports: [],
  providers: [
    {
      provide: HOOK_ROUTE,
      useValue: [
        {
          path: "sag-ps-pkg-mqtt-mapping/editor2-test",
          component: Editor2TestComponent,
        },
      ] as Route[],
      multi: true,
    },
  ],
})
export class Editor2TestModule {}
