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
import { NODE3, SharedModule } from '../shared';
import { ServiceConfigurationComponent } from './service-configuration.component';
import { BsDropdownModule } from 'ngx-bootstrap/dropdown';
import { SharedCodeComponent } from './sharedCode/shared-code.component';
import { EditorComponent, MonacoEditorMarkerValidatorDirective } from '@c8y/ngx-components/editor';
import { PopoverModule } from 'ngx-bootstrap/popover';

@NgModule({
  declarations: [ServiceConfigurationComponent, SharedCodeComponent],
  imports: [
    EditorComponent,
    MonacoEditorMarkerValidatorDirective,
    CoreModule,
    PopoverModule,
    SharedModule,
    BsDropdownModule.forRoot()
  ],
  exports: [],
  providers: [
    hookRoute({
      path: `sag-ps-pkg-dynamic-mapping/${NODE3}/serviceConfiguration`,
      component: ServiceConfigurationComponent,
    }),
    hookRoute({
      path: `sag-ps-pkg-dynamic-mapping/${NODE3}/sharedCode`,
      component: SharedCodeComponent,
    })
  ]
})
export class ServiceConfigurationModule { }
