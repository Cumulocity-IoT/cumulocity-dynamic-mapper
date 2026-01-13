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
      <span class="text-10 label label-primary">debug</span>
    }
    @switch (context.value.snoopStatus) {
      @case ('STARTED') {
        <span class="text-10 label label-primary">snoop: started</span>
      }
      @case ('STOPPED') {
        <span class="text-10 label label-primary">snoop: stopped</span>
      }
      @case ('ENABLED') {
        <span class="text-10 label label-primary">snoop: pending</span>
      }
    }
    `,
  standalone: true,
  imports: [CoreModule]
})
export class StatusRendererComponent {
  constructor(public readonly context: CellRendererContext) {}
}
