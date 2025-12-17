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
import { hookRoute } from '@c8y/ngx-components';
import { featureResolver } from '../shared';
import { DocMainComponent } from './doc-main.component';

@NgModule({
  providers: [
    hookRoute({
      path: 'c8y-pkg-dynamic-mapper/landing',
      component: DocMainComponent, resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: '',
      pathMatch: 'full',
      component: DocMainComponent, resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: 'c8y-pkg-dynamic-mapper/landing/jsonata',
      component: DocMainComponent, resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: 'c8y-pkg-dynamic-mapper/landing/javascript',
      component: DocMainComponent, resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: 'c8y-pkg-dynamic-mapper/landing/smartfunction',
      component: DocMainComponent, resolve: {
        feature: featureResolver
      }
    })
  ]
})
export class LandingModule { }
