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
import { Direction } from '../../shared';

@Component({
  template: `
    <span [title]="context.value">
      <i
        [style]="iconStyle"
        [c8yIcon]="iconName"
        class="m-r-5"
      ></i>
    </span>
  `,
  standalone: true,
  imports: [CoreModule]
})
export class DirectionRendererComponent {
  constructor(public readonly context: CellRendererContext) {}

  get isOutbound(): boolean {
    return this.context.value === Direction.OUTBOUND;
  }

  get iconStyle(): string {
    return this.isOutbound ? 'width: 100%; color: orange' : 'width: 100%; color: green';
  }

  get iconName(): string {
    return this.isOutbound ? 'swipe-left' : 'swipe-right';
  }
}
