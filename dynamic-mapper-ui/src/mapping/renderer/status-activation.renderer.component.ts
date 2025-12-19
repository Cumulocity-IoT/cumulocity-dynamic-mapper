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
import { CommonModule } from '@angular/common';
import { HttpStatusCode } from '@angular/common/http';
import { ChangeDetectorRef, Component, OnInit, ViewEncapsulation } from '@angular/core';
import { AlertService, CellRendererContext, CoreModule } from '@c8y/ngx-components';
import { Direction, Feature, SharedService } from '../../shared';
import { MappingService } from '../core/mapping.service';
import { SubscriptionService } from '../core/subscription.service';

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
          [disabled]="!canEdit || isCheckingValidity"
        />
        <span></span>
        <span class="text-capitalize">
          {{ context.value ? ('active' | translate) : ('inactive' | translate) }}
        </span>
      </label>
    </div>
  `,
  standalone: true,
  imports: [CoreModule, CommonModule]
})
export class MappingStatusActivationRendererComponent implements OnInit {
  feature: Feature;
  isCheckingValidity = false;

  constructor(
    public readonly context: CellRendererContext,
    private readonly alertService: AlertService,
    private readonly mappingService: MappingService,
    private readonly sharedService: SharedService,
    private readonly subscriptionService: SubscriptionService,
    private readonly cdr: ChangeDetectorRef
  ) {}

  get canEdit(): boolean {
    return this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole;
  }

  async ngOnInit() {
    try {
      this.feature = await this.sharedService.getFeatures();
      this.cdr.detectChanges();
    } catch (error) {
      console.error('Error loading features in component', error);
      this.alertService.danger('Failed to load features. Please refresh the page.');
    }
  }

  async onToggleClick(event: Event): Promise<void> {
    event.preventDefault();

    if (this.isCheckingValidity) {
      return;
    }

    this.isCheckingValidity = true;

    try {
      await this.activateMapping();
    } finally {
      this.isCheckingValidity = false;
      this.cdr.detectChanges();
    }
  }

  private async activateMapping(): Promise<void> {
    const { mapping } = this.context.item;
    const newActive = !mapping.active;

    if (mapping.direction === Direction.OUTBOUND && newActive) {
      const isValid = await this.validateSubscriptionOutbound();
      if (!isValid) {
        return;
      }
    }

    const response = await this.mappingService.changeActivationMapping({
      id: mapping.id,
      active: newActive
    });

    if (response.status !== HttpStatusCode.Created) {
      await this.handleActivationFailure(response);
    } else {
      this.handleActivationSuccess(newActive, mapping.name);
    }

    await this.refreshAllMappings();
  }

  private async validateSubscriptionOutbound(): Promise<boolean> {
    const [dynamicResult, staticResult] = await Promise.all([
      this.subscriptionService.getSubscriptionDevice(this.subscriptionService.DYNAMIC_DEVICE_SUBSCRIPTION),
      this.subscriptionService.getSubscriptionDevice(this.subscriptionService.STATIC_DEVICE_SUBSCRIPTION)
    ]);

    const hasSubscriptions = (dynamicResult.devices?.length > 0) || (staticResult.devices?.length > 0);

    if (!hasSubscriptions) {
      this.alertService.info(
        "To enable the outbound mapping, a subscription is required. Please proceed with creating the necessary 'Subscription outbound'."
      );
    }

    return hasSubscriptions;
  }

  private async handleActivationFailure(response: Response): Promise<void> {
    const failedMap: Record<string, string> = await response.json();
    const failedList = Object.values(failedMap).join(', ');
    this.alertService.warning(
      `Mapping could only activate partially. It failed for the following connectors: ${failedList}`
    );
  }

  private handleActivationSuccess(newActive: boolean, mappingName: string): void {
    const action = newActive ? 'Activated' : 'Deactivated';
    this.alertService.success(`${action} for mapping: ${mappingName} was successful`);
  }

  private async refreshAllMappings(): Promise<void> {
    await Promise.all([
      this.mappingService.refreshMappings(Direction.INBOUND),
      this.mappingService.refreshMappings(Direction.OUTBOUND)
    ]);
  }
}