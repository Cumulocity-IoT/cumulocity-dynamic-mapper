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
import { Observable, from, map, merge, mergeAll, of, toArray } from 'rxjs';
import { NODE3 } from '../shared';
@Injectable()
export class CodeTemplateTabFactory implements TabFactory {
  constructor(
    private router: Router,
  ) {}

  get(): Observable<Tab[]> {
    const tabs: Tab[] = [];
    if (this.router.url.match(/sag-ps-pkg-dynamic-mapping\/node3\/codeTemplate/g)) {
      tabs.push({
        path: `sag-ps-pkg-dynamic-mapping/${NODE3}/codeTemplate/inbound`,
        priority: 960,
        label: 'Inbound',
        icon: 'swipe-right',
        orientation: 'horizontal'
      } as Tab);
      tabs.push({
        path: `sag-ps-pkg-dynamic-mapping/${NODE3}/codeTemplate/outbound`,
        priority: 950,
        label: 'Outbound',
        icon: 'swipe-left',
        orientation: 'horizontal'
      } as Tab);
      tabs.push({
        path: `sag-ps-pkg-dynamic-mapping/${NODE3}/codeTemplate/others`,
        priority: 940,
        label: 'System, shared',
        icon: 'processor',
        orientation: 'horizontal'
      } as Tab);
      return merge(of(tabs)).pipe(mergeAll(), toArray());
    }
    return of(tabs);
  }
}
