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
import { hookNavigator, hookRoute } from '@c8y/ngx-components';
import { featureResolver } from '../shared';
import { DocMainComponent } from './doc-main.component';
import { DocNavigationFactory } from './doc-navigation.factory';
import { DocOverviewComponent } from './doc-overview.component';
import { DocJsonataComponent } from './doc-jsonata.component';
import { DocSmartFunctionComponent } from './doc-smartfunction.component';
import { DocJavaExtensionComponent } from './doc-javaextension.component';
import { DocCustomRoutingComponent } from './doc-customrouting.component';

const OVERVIEW_SECTIONS = [
  'overview', 'getting-started', 'managing-connectors', 'define-mapping',
  'sparkplugb', 'define-subscription-for-outbound', 'transformation-types',
  'flow-state', 'code-templates', 'metadata', 'unknown-payload',
  'reliability-settings', 'access-control', 'monitoring', 'message-explorer', 'troubleshooting'
];

@NgModule({
  providers: [
    hookRoute({
      path: '',
      pathMatch: 'full',
      redirectTo: 'c8y-pkg-dynamic-mapper/introduction'
    }),
    hookRoute({
      path: 'c8y-pkg-dynamic-mapper/introduction',
      component: DocMainComponent,
      resolve: { feature: featureResolver },
      children: [
        { path: '', pathMatch: 'full', component: DocOverviewComponent, resolve: { feature: featureResolver } },
        ...OVERVIEW_SECTIONS.map(s => ({ path: s, component: DocOverviewComponent, resolve: { feature: featureResolver } })),
        { path: 'jsonata',        component: DocJsonataComponent },
        { path: 'smartfunction',  component: DocSmartFunctionComponent },
        { path: 'javaextension',  component: DocJavaExtensionComponent },
        { path: 'custom-routing', component: DocCustomRoutingComponent },
      ]
    }),
    hookNavigator(DocNavigationFactory),
  ]
})
export class LandingModule { }
