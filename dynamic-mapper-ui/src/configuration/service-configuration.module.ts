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
import { CoreModule, hookRoute, hookTab } from '@c8y/ngx-components';
import { featureResolver, NODE3, SharedModule } from '../shared';
import { ServiceConfigurationComponent } from './service-configuration.component';
import { BsDropdownModule } from 'ngx-bootstrap/dropdown';
import { CodeComponent } from './code-template/code-template.component';
import { EditorComponent, MonacoEditorMarkerValidatorDirective } from '@c8y/ngx-components/editor';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { CodeTemplateTabFactory } from './code-template-tab.factory';
import { ServiceConfigurationTraceComponent } from './service-configuration-trace.component';

@NgModule({
  //declarations: [ServiceConfigurationComponent, ServiceConfigurationTraceComponent, CodeComponent],
  declarations: [ServiceConfigurationComponent, ServiceConfigurationTraceComponent],
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
    hookRoute({
      path: `c8y-pkg-dynamic-mapper/${NODE3}/c8ySelect`,
      component: ServiceConfigurationTraceComponent,
      resolve: {
        feature: featureResolver
      }
    }),
    hookTab(CodeTemplateTabFactory)
  ]
})
export class ServiceConfigurationModule { }
