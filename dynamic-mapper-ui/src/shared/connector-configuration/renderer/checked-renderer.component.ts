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

import { Component, ViewEncapsulation } from '@angular/core';
import { AlertService, CellRendererContext } from '@c8y/ngx-components';

/**
 * The example component for custom cell renderer.
 * It gets `context` with the current row item and the column.
 * Additionally, a service is injected to provide a helper method.
 * The template displays the icon and the label with additional styling.
 */
@Component({
  encapsulation: ViewEncapsulation.None,
  selector: 'd11r-mapping-renderer-checked',
  template: `
    <div>
      <label class="c8y-checkbox">
        <input type="checkbox" [checked]="context.value" disabled />
		<span></span>
      </label>
    </div>
  `,
  standalone: true,
  imports: []
})
export class CheckedRendererComponent {
  constructor(
    public context: CellRendererContext,
    public alertService: AlertService
  ) {
     // console.log('Checked', context, context.value);
  }
}
