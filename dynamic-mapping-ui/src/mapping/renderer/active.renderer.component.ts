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
import { Component } from '@angular/core';
import { AlertService, CellRendererContext } from '@c8y/ngx-components';
import { MappingService } from '../core/mapping.service';

@Component({
  template: `
    <div>
      <label class="c8y-switch c8y-switch--inline">
        <input
          type="checkbox"
          [(ngModel)]="active"
          (change)="onActivate()"
        />
        <span></span>
      </label>
    </div>
  `
})
export class ActiveRendererComponent {
  constructor(
    public context: CellRendererContext,
    public mappingService: MappingService,
    public alertService: AlertService
  ) {
    // console.log("Active renderer:", context.item.active)
    this.active = context.item.active;
  }

  active: boolean;

  async onActivate() {
    const action = this.active ? 'Activate' : 'Deactivate';
    this.alertService.success(
      `${action } mapping: ${ this.context.item.id }!`
    );
    const parameter = { id: this.context.item.id, active: this.active };
    await this.mappingService.changeActivationMapping(parameter);
    this.mappingService.reloadMappings();
  }
}
