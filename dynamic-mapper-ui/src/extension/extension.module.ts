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
import { CoreModule, hookRoute } from '@c8y/ngx-components';
import { CollapseModule } from 'ngx-bootstrap/collapse';
import { BsDropdownModule } from 'ngx-bootstrap/dropdown';
import { NODE3, SharedModule } from '../shared';
import { ExtensionCardComponent } from './card/extension-card.component';
import { AddExtensionComponent } from './add/add-extension-modal.component';
import { ExtensionGridComponent } from './grid/extension-grid.component';
import { ExtensionPropertiesComponent } from './properties/extension-properties.component';
import { extensionResolver } from './share/extension.model';

@NgModule({
  declarations: [
    ExtensionGridComponent,
    AddExtensionComponent,
    ExtensionCardComponent,
    ExtensionPropertiesComponent
  ],
  imports: [
    CoreModule,
    BsDropdownModule.forRoot(),
    CollapseModule.forRoot(),
    SharedModule
  ],
  exports: [],
  providers: [
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE3}/processorExtension`,
      children: [
        {
          path: '',
          pathMatch: 'full',
          component: ExtensionGridComponent
        },
        {
          path: 'properties/:id',
          component: ExtensionPropertiesComponent,
          resolve: { extensions: extensionResolver }
        }
      ]
    })
  ]
})
export class ExtensionModule {}

export enum ExtensionStatus {
  COMPLETE = 'COMPLETE',
  PARTIALLY = 'PARTIALLY',
  NOT_LOADED = 'NOT_LOADED',
  UNKNOWN = 'UNKNOWN'
}
