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

/**
 * Renders the state of a version row as a colored label. Expects
 * {@code context.value} to be one of 'active' | 'published' | 'draft'.
 */
@Component({
  selector: 'd11r-version-state-cell',
  template: `
    @switch (context.value) {
      @case ('active') {
        <span class="label label-primary" [attr.data-cy]="'dm-version-state-active'">{{ 'active' | translate }}</span>
      }
      @case ('draft') {
        <span class="label label-info" [attr.data-cy]="'dm-version-state-draft'">{{ 'draft' | translate }}</span>
      }
      @default {
        <span class="label label-default" [attr.data-cy]="'dm-version-state-published'">{{ 'published' | translate }}</span>
      }
    }
  `,
  standalone: true,
  imports: [CoreModule]
})
export class VersionStateCellRendererComponent {
  constructor(public readonly context: CellRendererContext) { }
}
