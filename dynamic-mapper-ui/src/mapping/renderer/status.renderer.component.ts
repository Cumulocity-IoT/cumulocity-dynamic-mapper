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

import { Component } from '@angular/core';
import { CellRendererContext, CoreModule } from '@c8y/ngx-components';

@Component({
  selector: 'd11r-mapping-renderer-status',
  template: `
      @if (context.value.debug) {
        <div class="d-flex flex-col">
        <span class="text-12 label label-success">debug</span>
      </div>
      }
      @switch (context.value.snoopStatus) {
        @case ('STARTED') {
          <div class="d-flex flex-col">
            <span class="text-12 label label-success">snoop: started</span>
          </div>
        }
        @case ('STOPPED') {
          <div class="d-flex flex-col">
            <span class="text-12 label label-success">snoop: stopped</span>
          </div>
        }
        @case ('ENABLED') {
          <div class="d-flex flex-col">
            <span class="text-12 label label-success">snoop: pending</span>
          </div>
        }

      }
    `,
  standalone: true,
  imports: [CoreModule]
})
export class StatusRendererComponent {
  constructor(public readonly context: CellRendererContext) { }
}
