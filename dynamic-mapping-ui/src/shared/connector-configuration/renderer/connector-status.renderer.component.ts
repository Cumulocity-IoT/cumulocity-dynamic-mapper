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
import { Component } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';

@Component({
  selector: 'd11r-mapping-renderer-qos',
  template: `<span
    [class]="
      'text-12 tag ' +
      ((context.item?.status$ | async) === 'CONNECTED'
        ? 'tag--success'
        : (context.item?.status$ | async) === 'FAILED'
          ? 'tag--danger'
          : 'tag--default')
    "
    >{{ context.item?.status$ | async }}
  </span> `
})
export class ConnectorStatusRendererComponent {
  constructor(public context: CellRendererContext) {
    // console.log(   `Connector: ${context.item.name}`, context.item?.status$ | async);
    // const status$ = context.item?.status$;
    // status$.subscribe( n => console.log(`Connector new status: ${n}`));
  }
}