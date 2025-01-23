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
import { Component, Inject } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';
import { TestingDeviceService } from '../testing.service';

/**
 * The example component for custom cell renderer.
 * It gets `context` with the current row item and the column.
 * Additionally, a service is injected to provide a helper method.
 * The template displays the icon and the label with additional styling.
 */
@Component({
  template: `
    <span>
      <a
        href="{{
          '/apps/devicemanagement/index.html#/device/' + context.item.id
        }}"
        title="{{ context.item.id }}"
        target="_blank"
        class="text-primary"
        >{{ context.item.id }}</a
      >
    </span>
  `
})
export class DeviceIdCellRendererComponent {
  constructor(
    public context: CellRendererContext,
    @Inject(TestingDeviceService) public service: TestingDeviceService
  ) {}
}
