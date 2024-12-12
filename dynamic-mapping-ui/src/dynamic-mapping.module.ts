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
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import {
  CommonModule,
  CoreModule,
  DynamicFormsModule,
  hookNavigator,
  hookTab,
  RealtimeModule
} from '@c8y/ngx-components';
import { BsModalService, ModalModule } from 'ngx-bootstrap/modal';
import { ServiceConfigurationModule } from './configuration/service-configuration.module';
import { ExtensionModule } from './extension/extension.module';
import { MappingTreeModule } from './mapping-tree/tree.module';
import { MappingModule } from './mapping/mapping.module';
import { MonitoringModule } from './monitoring/monitoring.module';
import { FormlyPresetModule } from '@ngx-formly/core/preset';

import {
  MappingNavigationFactory,
  MappingTabFactory,
  OverviewGuard,
  SharedModule,
} from './shared';
import { TestingModule } from './testing-device/testing.module';
import './shared/styles/shared.css';
import { BrokerConnectorModule } from './connector';
import { LandingModule } from './landing/landing.module';

@NgModule({
  imports: [
    CoreModule,
    LandingModule,
    TestingModule,
    MappingModule,
    MappingTreeModule,
    MonitoringModule,
    ServiceConfigurationModule,
    BrokerConnectorModule,
    ExtensionModule,
    FormsModule,
    ModalModule,
    ReactiveFormsModule,
    DynamicFormsModule,
    CommonModule,
    DynamicFormsModule,
    FormlyPresetModule,
    SharedModule,
    RealtimeModule
  ],
  exports: [],
  declarations: [],
  providers: [
    OverviewGuard,
    BsModalService,
    hookNavigator(MappingNavigationFactory),
    hookTab(MappingTabFactory),
  ]
})
export class DynamicMappingModule {
  constructor() {}
}
