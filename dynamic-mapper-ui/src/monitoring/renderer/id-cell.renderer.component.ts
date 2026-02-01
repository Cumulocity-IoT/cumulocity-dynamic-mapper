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
import { CellRendererContext, CommonModule } from '@c8y/ngx-components';

const UNSPECIFIED_ID = -1;
const UNSPECIFIED_LABEL = 'UNSPECIFIED';

@Component({
  template: `<span>{{ displayValue }}</span>`,
  standalone: true,
  imports: []
})
export class IdRendererComponent {
  constructor(public readonly context: CellRendererContext) {}

  get displayValue(): string | number {
    return this.context.item.id === UNSPECIFIED_ID ? UNSPECIFIED_LABEL : this.context.item.id;
  }
}
