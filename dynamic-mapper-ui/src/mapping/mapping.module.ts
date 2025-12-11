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
import {
  hookRoute,
  hookTab} from '@c8y/ngx-components';
import { DeviceGridService } from '@c8y/ngx-components/device-grid';
import { FORMLY_CONFIG } from '@ngx-formly/core';
import { NODE1, featureResolver } from '../shared';
import { MappingComponent } from './grid/mapping.component';
import { checkTopicsInboundAreValid, checkTopicsOutboundAreValid } from './shared/util';
import { MappingSubscriptionComponent } from './subscription/subscription.component';
import { DeviceClientMapComponent } from './client-relation/device-client-map.component';
import { MappingTabFactory } from './mapping-tab.factory';
import { SubstitutionManagementService } from './service/substitution-management.service';
import { MappingStepperService } from './service/mapping-stepper.service';

@NgModule({
  providers: [
    DeviceGridService,
    SubstitutionManagementService,
    MappingStepperService,

    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE1}/mappings/inbound`,
      component: MappingComponent,
      resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE1}/mappings/outbound`,
      component: MappingComponent,
      resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE1}/mappings/subscription/static`,
      component: MappingSubscriptionComponent,
      resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE1}/mappings/subscription/dynamic`,
      component: MappingSubscriptionComponent,
      resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE1}/mappings/relation/deviceToClientMap`,
      component: DeviceClientMapComponent,
      resolve: {
        feature: featureResolver
      }
    }),
    hookTab(MappingTabFactory),
    {
      provide: FORMLY_CONFIG,
      multi: true,
      useValue: {
        validators: [
          {
            name: 'checkTopicsInboundAreValid',
            validation: checkTopicsInboundAreValid
          },
          {
            name: 'checkTopicsOutboundAreValid',
            validation: checkTopicsOutboundAreValid
          }
        ],

      }
    }
  ]
})
export class MappingModule { }
