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
import { SnoopStatus } from '../../shared';

@Component({
  template: `
    <div class="d-inline-flex">
      <!-- <div class="c8y-realtime" title="Tested">
        <span
          class="c8y-pulse animated-slow pulse"
          [ngClass]="{
            active: context.value.tested,
            inactive: !context.value.tested
          }"
        ></span>
      </div> -->
      <div class="c8y-realtime" title="Debug">
        <span
          class="c8y-pulse animated-slow  pulse"
          [ngClass]="{
            active: context.value.debug,
            inactive: !context.value.debug
          }"
        ></span>
      </div>
      <div class="c8y-realtime" [title]="'Snooping:' + context.value.snoopStatus">
        <span
          class="c8y-pulse animated-slow pulse"
          [ngClass]="{
            active:
              context.value.snoopStatus === 'STARTED',
            inactive:
              context.value.snoopStatus === 'NONE' ||
              context.value.snoopStatus === 'STOPPED'
          }"
        ></span>
      </div>
    </div>
  `,
  styles: ['.animated-slow { animation-duration: 10s;}']
})
export class StatusRendererComponent {
  constructor(public context: CellRendererContext) {
    // console.log('StatusRenderer:', context.item, context.value);
  }
  SnoopStatus: SnoopStatus;
}
