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
import { connectorResolver, ConnectorDetailsComponent, SharedModule } from '../shared';
import { ConnectorConfigurationComponent } from './connector-configuration.component';
import { featureResolver, NODE3 } from '../shared/mapping/util';

@NgModule({
  imports: [
    SharedModule
  ],
  exports: [],
  providers: [
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE3}/connectorConfiguration`,
      children: [
        {
          path: '',
          pathMatch: 'full',
          component: ConnectorConfigurationComponent,
          resolve: { feature: featureResolver }
        },
        {
          path: 'details/:identifier',
          component: ConnectorDetailsComponent,
          resolve: { connector: connectorResolver, feature: featureResolver  }
        }
      ]
    })
  ]
})
export class BrokerConnectorModule { }
