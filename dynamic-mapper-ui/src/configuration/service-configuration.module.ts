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
import { hookRoute, hookTab } from '@c8y/ngx-components';
import { featureResolver, NODE3 } from '../shared';
import { ServiceConfigurationComponent } from './service-configuration.component';
import { CodeComponent } from './code-template/code-template.component';
import { CodeTemplateTabFactory } from './code-template-tab.factory';

@NgModule({
  providers: [
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE3}/serviceConfiguration`,
      component: ServiceConfigurationComponent,
      resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE3}/codeTemplate/INBOUND_SUBSTITUTION_AS_CODE`,
      component: CodeComponent,
      resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE3}/codeTemplate/OUTBOUND_SUBSTITUTION_AS_CODE`,
      component: CodeComponent,
      resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE3}/codeTemplate/INBOUND_SMART_FUNCTION`,
      component: CodeComponent,
      resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE3}/codeTemplate/OUTBOUND_SMART_FUNCTION`,
      component: CodeComponent,
      resolve: {
        feature: featureResolver
      }
    }),
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE3}/codeTemplate/others`,
      component: CodeComponent,
      resolve: {
        feature: featureResolver
      }
    }),
    hookTab(CodeTemplateTabFactory)
  ]
})
export class ServiceConfigurationModule { }
