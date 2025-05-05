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
import { CellRendererContext } from '@c8y/ngx-components';

@Component({
  selector: 'd11r-mapping-renderer-api',
  template: `
<div>
    <span *ngIf="!isArray(context.value)" class="text-10 label label-primary">
        {{ context.value }}
    </span>

    <ng-container *ngIf="isArray(context.value)">
        <div *ngFor="let item of context.value" >
          <!-- <span class="text-10 label label-primary"> -->
          <span class="text-12 tag tag--success">
              {{ item }}
          </span>
        </div>
    </ng-container>
</div>
  `,
  standalone: false
})
export class LabelRendererComponent {
  constructor(public context: CellRendererContext) {
    // console.log("Context:", context.item, context)
  }
  isArray(value: any): boolean {
    return Array.isArray(value);
}
}
