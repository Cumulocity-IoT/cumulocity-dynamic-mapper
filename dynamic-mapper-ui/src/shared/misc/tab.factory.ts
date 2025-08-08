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
import { SharedService } from '../service/shared.service';
import { NODE1, NODE2, NODE3 } from '../mapping/util';

@Injectable()
export class MappingTabFactory implements TabFactory {
  constructor(
    public router: Router,
    private sharedService: SharedService
  ) {}

  async get() {
    // console.log("MappingTabFactory",this.router.url, this.router.url.match(/c8y-pkg-dynamic-mapper/g));
    const feature = await this.sharedService.getFeatures();

    const tabs: Tab[] = [];
    if (this.router.url.match(/c8y-pkg-dynamic-mapper/g)) {
      tabs.push({
        path: `c8y-pkg-dynamic-mapper/${NODE1}/mappings/subscription/dynamic`,
        priority: 920,
        label: 'Subscription dynamic',
        icon: 'automatic',
        orientation: 'horizontal',
      } as Tab);
      tabs.push({
        path: `c8y-pkg-dynamic-mapper/${NODE1}/mappings/subscription/static`,
        priority: 930,
        label: 'Subscription static',
        icon: 'check-box',
        orientation: 'horizontal',
      } as Tab);
    }
    return tabs;
  }
}