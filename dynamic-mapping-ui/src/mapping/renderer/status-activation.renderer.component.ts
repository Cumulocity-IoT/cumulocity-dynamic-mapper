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
import { Component, OnInit, ViewEncapsulation } from '@angular/core';
import { AlertService, CellRendererContext } from '@c8y/ngx-components';
import { MappingService } from '../core/mapping.service';
import { Direction, Feature, SharedService } from '../../shared';
import { HttpStatusCode } from '@angular/common/http';

/**
 * The example component for custom cell renderer.
 * It gets `context` with the current row item and the column.
 * Additionally, a service is injected to provide a helper method.
 * The template displays the icon and the label with additional styling.
 */
@Component({
  encapsulation: ViewEncapsulation.None,
  selector: 'd11r-mapping-renderer-activation',
  template: `
    <div>
      <label
        title="{{ 'Toggle mapping activation' | translate }}"
        class="c8y-switch"
      >
        <input
          type="checkbox"
          [checked]="context.value"
          (change)="activateMapping()"
          [disabled]="!feature?.userHasMappingAdminRole"

        />
        <span></span>
        <span
          class="text-capitalize"
          title="{{
            context.value ? 'active' : ('inactive' | translate | lowercase)
          }}"
        >
          {{ context.value ? 'active' : ('inactive' | translate) }}
        </span>
      </label>
    </div>
  `,
  standalone: false
})
export class StatusActivationRendererComponent implements OnInit {
    feature: Feature;
  constructor(
    public context: CellRendererContext,
    public alertService: AlertService,
    public mappingService: MappingService,
    public sharedService: SharedService,
  ) {
    // console.log('Status', context, context.value);
  }
  async ngOnInit() {
        this.feature = await this.sharedService.getFeatures();
  }
  
  async activateMapping() {
    const { mapping } = this.context.item;
    const newActive = !mapping.active;
    const action = newActive ? 'Activated' : 'Deactivated';
    const parameter = { id: mapping.id, active: newActive };
    const response =
      await this.mappingService.changeActivationMapping(parameter);
    if (response.status != HttpStatusCode.Created) {
      const failedMap = await response.json();
      const failedList = Object.values(failedMap).join(',');
      this.alertService.warning(
        `Mapping could only activate partially. It failed for the following connectors: ${failedList}`
      );
    } else {
      this.alertService.success(`${action} for mapping: ${mapping.name} was successful`);
    }
    this.mappingService.refreshMappings(Direction.INBOUND);
    this.mappingService.refreshMappings(Direction.OUTBOUND);
  }
}
