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
import { NODE2 } from '../shared/mapping/util';
@Injectable()
export class MonitoringTabFactory implements TabFactory {
  constructor(
    public router: Router
  ) {}

  async get() {
    // console.log("MonitoringTabFactory",this.router.url, this.router.url.match(/sag-ps-pkg-dynamic-mapping/g));
    const tabs: Tab[] = [];
	// const re = new RegExp(String.raw`sag-ps-pkg-dynamic-mapping/${NODE2}/monitoring/grid`, 'g');
    if (this.router.url.match(/sag-ps-pkg-dynamic-mapping\/node2/g)) {
	// if (this.router.url.match(re)) {
      tabs.push({
        path: `sag-ps-pkg-dynamic-mapping/${NODE2}/monitoring/grid`,
        priority: 810,
        label: 'Monitoring mapping',
        icon: 'monitoring',
        orientation: 'horizontal'
      } as Tab);
      tabs.push({
        path: `sag-ps-pkg-dynamic-mapping/${NODE2}/monitoring/chart`,
        priority: 800,
        label: 'Monitoring chart',
        icon: 'pie-chart',
        orientation: 'horizontal'
      } as Tab);
    }
    return tabs;
  }
}
