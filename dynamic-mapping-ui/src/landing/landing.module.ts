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
import { RouterModule } from '@angular/router';
import { CoreModule, hookRoute } from '@c8y/ngx-components';
import { CollapseModule } from 'ngx-bootstrap/collapse';
import { BsDropdownModule } from 'ngx-bootstrap/dropdown';
import { MappingModule } from '../mapping/mapping.module';
import { featureResolver, SharedModule } from '../shared';
import { LandingComponent } from './landing.component';

@NgModule({
  declarations: [LandingComponent],
  imports: [
    CoreModule,
    BsDropdownModule.forRoot(),
    CollapseModule.forRoot(),
    MappingModule,
    SharedModule,
    RouterModule
  ],
  exports: [],
  providers: [
    hookRoute({
      path: 'sag-ps-pkg-dynamic-mapping/landing',
      component: LandingComponent, resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: '',
      pathMatch: 'full',
      component: LandingComponent, resolve: {
        feature: featureResolver
      }
    })
  ]
})
export class LandingModule { }
