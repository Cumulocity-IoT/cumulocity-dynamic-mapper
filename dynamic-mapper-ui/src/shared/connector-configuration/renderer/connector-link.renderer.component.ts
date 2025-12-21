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
import { RouterLink } from '@angular/router';
import { CellRendererContext, CoreModule } from '@c8y/ngx-components';

/**
 * The example component for custom cell renderer.
 * It gets `context` with the current row item and the column.
 * Additionally, a service is injected to provide a helper method.
 * The template displays the icon and the label with additional styling.
 */
@Component({
  template: `
    <a
      class="interact"
      [title]="context.item.name"
      *ngIf="context?.property['callback']; else router"
      (click)="context.property['callback'](context.item)"
    >
      {{ context.item.name }}
    </a>
    <ng-template #router>
      <a class="interact" [title]="context.item.name" [routerLink]="['details/' + context.item.identifier]">
        {{ context.item.name }}
      </a>
    </ng-template>
  `,
  standalone: true,
  imports: [
    CoreModule,
    RouterLink
  ]
})
export class ConnectorDetailCellRendererComponent {
  constructor(public context: CellRendererContext) { }
}
