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
import { Component, ViewEncapsulation } from '@angular/core';
import { AlertService, CellRendererContext } from '@c8y/ngx-components';
import { MappingService } from '../core/mapping.service';
import { Direction } from '../../shared';

/**
 * The example component for custom cell renderer.
 * It gets `context` with the current row item and the column.
 * Additionally, a service is injected to provide a helper method.
 * The template displays the icon and the label with additional styling.
 */
@Component({
  encapsulation: ViewEncapsulation.None,
    template: `
        <div >
          <label
            title="{{ 'Toggle mapping activation' | translate }}"
            class="c8y-switch"
          >
            <input
              type="checkbox"
              [checked]="context.value"
              (change)="activateMapping()"
            />
            <span></span>
            <span
              class="text-capitalize"
              title="{{ context.value ? 'active': 'inactive'| translate | lowercase }}"
            >
              {{ context.value ? 'active': 'inactive' | translate }}
            </span>
          </label>
        </div>
  `
})
export class StatusActivationRendererComponent {
  constructor(public context: CellRendererContext, public alertService:AlertService, public mappingService: MappingService) {
     // console.log('Status', context, context.value);
  }

  async activateMapping() {
    const { mapping } = this.context.item;
    const newActive = !mapping.active;
    const action = newActive ? 'Activated' : 'Deactivated';
    this.alertService.success(`${action} mapping: ${mapping.id}!`);
    const parameter = { id: mapping.id, active: newActive };
    await this.mappingService.changeActivationMapping(parameter);
    this.mappingService.refreshMappings(Direction.INBOUND);
    this.mappingService.refreshMappings(Direction.OUTBOUND);
  }
}
