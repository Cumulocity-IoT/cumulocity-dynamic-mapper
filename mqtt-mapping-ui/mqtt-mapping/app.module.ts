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
import { NgModule } from '@angular/core';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { RouterModule as ngRouterModule } from '@angular/router';
import { BootstrapComponent, CoreModule, hookWizard, RouterModule } from '@c8y/ngx-components';
import { BsModalRef } from 'ngx-bootstrap/modal';
import { MQTTMappingModule } from './src/service-mapping.module';
import { MappingTypeComponent } from './src/mqtt-mapping/mapping-type/mapping-type.component';
import { AddExtensionWizardComponent } from './src/mqtt-extension/add-extension-wizard.component';

@NgModule({
  declarations: [],
  imports: [
    BrowserAnimationsModule,
    ngRouterModule.forRoot([], { enableTracing: false, useHash: true }),
    RouterModule.forRoot(),
    CoreModule.forRoot(),
    MQTTMappingModule,
  ],
  providers: [
    BsModalRef,
    hookWizard({
      wizardId: 'uploadExtensionWizard',
      component: AddExtensionWizardComponent,
      name: 'Upload Extension',
      c8yIcon: 'upload'
    }),
    hookWizard({
      wizardId: 'addMappingWizard',
      component: MappingTypeComponent,
      name: 'App mapping',
      c8yIcon: 'plus-circle'
    }),
  ],
  entryComponents: [MappingTypeComponent, AddExtensionWizardComponent],
  bootstrap: [BootstrapComponent]
})
export class AppModule { }
