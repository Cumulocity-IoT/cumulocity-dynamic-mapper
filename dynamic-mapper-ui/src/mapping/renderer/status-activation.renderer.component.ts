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
import { ChangeDetectorRef, Component, OnInit, ViewEncapsulation } from '@angular/core';
import { AlertService, CellRendererContext, CoreModule } from '@c8y/ngx-components';
import { MappingService } from '../core/mapping.service';
import { Direction, Feature, Mapping, SharedService } from '../../shared';
import { HttpStatusCode } from '@angular/common/http';
import { SubscriptionService } from '../core/subscription.service';
import { CommonModule } from '@angular/common';

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
        (click)="onToggleClick($event)"
        [disabled]="
          !(feature?.userHasMappingAdminRole || feature?.userHasMappingCreateRole) ||
          isCheckingValidity
        "
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
  standalone: true,
  imports: [CoreModule, CommonModule]

})

export class MappingStatusActivationRendererComponent implements OnInit {
  constructor(
    public context: CellRendererContext,
    public alertService: AlertService,
    public mappingService: MappingService,
    public sharedService: SharedService,
    public subscriptionService: SubscriptionService,
    private cdr: ChangeDetectorRef
  ) {
    // console.log('Status', context, context.value);
  }

  feature: Feature;
  isCheckingValidity: boolean = false;

  async ngOnInit() {
    try {
      this.feature = await this.sharedService.getFeatures();
      this.cdr.detectChanges();
    } catch (error) {
      console.error('Error loading features in component', error);
    }
  }

  async onToggleClick(event: Event) {
    event.preventDefault(); // Prevent the checkbox from toggling visually
    await this.activateMapping();
  }

  async activateMapping() {
    const { mapping } = this.context.item;
    const newActive = !mapping.active;

    // Prevent multiple simultaneous clicks
    if (this.isCheckingValidity) {
      return;
    }

    this.isCheckingValidity = true;

    // Validate subscription ONLY for OUTBOUND mappings BEFORE activating
    if (mapping.direction === Direction.OUTBOUND && newActive) {
      const valid = await this.validateSubscriptionOutbound(mapping);
      if (!valid) {
        this.isCheckingValidity = false;
        this.cdr.detectChanges();
        return; // Exit without toggling
      }
    }

    const action = newActive ? 'Activated' : 'Deactivated';
    const parameter = { id: mapping.id, active: newActive };

    const response = await this.mappingService.changeActivationMapping(parameter);
    if (response.status != HttpStatusCode.Created) {
      const failedMap = await response.json();
      const failedList = Object.values(failedMap).join(',');
      this.alertService.warning(
        `Mapping could only activate partially. It failed for the following connectors: ${failedList}`
      );
    } else {
      this.alertService.success(`${action} for mapping: ${mapping.name} was successful`);
    }

    this.isCheckingValidity = false;
    this.mappingService.refreshMappings(Direction.INBOUND);
    this.mappingService.refreshMappings(Direction.OUTBOUND);
  }

  private async validateSubscriptionOutbound(mapping: Mapping): Promise<boolean> {
    const result = await Promise.all([
      this.subscriptionService.getSubscriptionDevice(this.subscriptionService.DYNAMIC_DEVICE_SUBSCRIPTION),
      this.subscriptionService.getSubscriptionDevice(this.subscriptionService.STATIC_DEVICE_SUBSCRIPTION)
    ]);

    if (result[0].devices?.length === 0 && result[1].devices?.length === 0) {
      this.alertService.info(
        "To enable the outbound mapping, a subscription is required. Please proceed with creating the necessary 'Subscription outbound'."
      );
      return false;
    }
    return true;
  }
}