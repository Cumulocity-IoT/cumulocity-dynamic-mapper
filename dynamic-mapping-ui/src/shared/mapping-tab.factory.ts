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
import { Injectable } from '@angular/core';
import { TabFactory, Tab } from '@c8y/ngx-components';
import { Router } from '@angular/router';
import { SharedService } from './shared.service';
import { NODE1, NODE2, NODE3 } from './model/util';
@Injectable()
export class MappingTabFactory implements TabFactory {
  constructor(
    public router: Router,
    private sharedService: SharedService
  ) {}

  async get() {
    // console.log("MappingTabFactory",this.router.url, this.router.url.match(/sag-ps-pkg-dynamic-mapping/g));
    const feature = await this.sharedService.getFeatures();

    const tabs: Tab[] = [];
    if (this.router.url.match(/sag-ps-pkg-dynamic-mapping\/node1/g)) {
      tabs.push({
        path: `sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/inbound`,
        priority: 930,
        label: 'Inbound mapping',
        icon: 'swipe-right',
        orientation: 'horizontal'
      } as Tab);

      if (feature?.outputMappingEnabled) {
        tabs.push({
          path: `sag-ps-pkg-dynamic-mapping/${NODE1}/mappings/outbound`,
          priority: 920,
          label: 'Outbound mapping',
          icon: 'swipe-left',
          orientation: 'horizontal'
        } as Tab);
      }
      // if (feature?.userHasMappingAdminRole) {
      tabs.push({
        path: `sag-ps-pkg-dynamic-mapping/${NODE1}/configuration`,
        priority: 910,
        label: 'Connector',
        icon: 'connected',
        orientation: 'horizontal'
      } as Tab);
      // }
    } else if (this.router.url.match(/sag-ps-pkg-dynamic-mapping\/node2/g)) {
      // if (feature?.userHasMappingAdminRole) {

      tabs.push({
        path: `sag-ps-pkg-dynamic-mapping/${NODE2}/testing`,
        priority: 700,
        label: 'Test device',
        icon: 'reflector-bulb',
        orientation: 'horizontal'
      } as Tab);
    } else if (this.router.url.match(/sag-ps-pkg-dynamic-mapping\/node3/g)) {
      tabs.push({
        path: `sag-ps-pkg-dynamic-mapping/${NODE3}/extension`,
        priority: 500,
        label: 'Processor extension',
        icon: 'plugin',
        orientation: 'horizontal'
      } as Tab);
      tabs.push({
        path: `sag-ps-pkg-dynamic-mapping/${NODE3}/configuration`,
        priority: 500,
        label: 'Service configuration',
        icon: 'cog',
        orientation: 'horizontal'
      } as Tab);
    }
    return tabs;
  }
}
