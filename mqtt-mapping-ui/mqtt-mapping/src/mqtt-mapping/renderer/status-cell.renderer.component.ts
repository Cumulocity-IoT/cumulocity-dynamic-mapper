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
import { SnoopStatus } from '../../shared/mapping.model';

@Component({
  template: `<div class="c8y-realtime" title="Active">
  <span class="c8y-pulse animated pulse" [ngClass]="{
      active: context.item.active,
      inactive: !context.item.active
    }"></span>
</div>
<div class="c8y-realtime" title="Tested">
  <span class="c8y-pulse animated pulse" [ngClass]="{
      active: context.item.tested,
      inactive: !context.item.tested
    }" ></span>
</div>
<div class="c8y-realtime" title="Snooping">
  <span class="c8y-pulse animated pulse" [ngClass]="{
      active: context.item.snoopStatus == 'ENABLED' || context.item.snoopStatus == 'STARTED',
      inactive: context.item.snoopStatus == 'NONE' || context.item.snoopStatus == 'STOPPED'
    }"></span>
</div>
`
})
export class StatusRendererComponent {
  constructor(
    public context: CellRendererContext,
  ) { }
  SnoopStatus: SnoopStatus;
}