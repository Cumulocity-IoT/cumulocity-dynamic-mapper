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

import { Injectable } from '@angular/core';
import { TabFactory, Tab } from '@c8y/ngx-components';
import { Router } from '@angular/router';
import { NODE2 } from '../shared/mapping/util';

@Injectable()
export class VersionsTabFactory implements TabFactory {
  constructor(public router: Router) {}

  async get(): Promise<Tab[]> {
    const tabs: Tab[] = [];
    if (this.router.url.match(/c8y-pkg-dynamic-mapper\/node2\/monitoring\/versions/g)) {
      tabs.push({
        path: `c8y-pkg-dynamic-mapper/${NODE2}/monitoring/versions/inbound`,
        priority: 930,
        label: 'Inbound',
        icon: 'swipe-right',
        orientation: 'horizontal'
      } as Tab);
      tabs.push({
        path: `c8y-pkg-dynamic-mapper/${NODE2}/monitoring/versions/outbound`,
        priority: 920,
        label: 'Outbound',
        icon: 'swipe-left',
        orientation: 'horizontal'
      } as Tab);
    }
    return tabs;
  }
}
