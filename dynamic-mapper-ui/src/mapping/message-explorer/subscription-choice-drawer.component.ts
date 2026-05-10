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

import { AfterViewInit, ChangeDetectorRef, Component, inject, Input, ViewEncapsulation } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AlertService, BottomDrawerRef, CoreModule } from '@c8y/ngx-components';
import { API } from '../../shared';
import { SubscriptionService } from '../core/subscription.service';
import { Device } from '../shared/mapping.model';

export type SubscriptionChoice = 'skip' | 'type' | 'group';

export interface DeviceGroupInfo {
  id: string;
  name: string;
}

@Component({
  selector: 'd11r-subscription-choice-drawer',
  host: { class: 'flex-grow d-col fit-h' },
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, CommonModule, FormsModule],
  template: `
    <div class="d-col flex-nowrap no-align-items p-48 flex-grow col-md-12 col-md-offset-0 c8y-stepper--no-btns">
      <div class="card card--fullpage d-col flex-grow">
        <div class="card-header separator j-c-center">
          <h4 id="drawerTitle" class="card-title d-flex">
            <i c8yIcon="subscription" class="icon-32 m-r-16"></i>
            <span class="m-t-8" translate>Outbound subscription</span>
          </h4>
        </div>
        <div class="card-inner-scroll flex-grow">
          <div class="card-block">
            <p class="text-muted" translate>
              Outbound mappings require a device subscription so Cumulocity pushes events to the broker.
              Would you like to set one up now?
            </p>

            <c8y-list-group class="separator-top m-t-24" role="list">

              <!-- Skip -->
              <c8y-li role="listitem">
                <c8y-li-radio name="subscriptionChoice" [value]="'skip'" [(ngModel)]="choice"></c8y-li-radio>
                <c8y-li-icon icon="forward"></c8y-li-icon>
                <c8y-li-body>
                  <strong translate>Skip for now</strong>
                  <p class="text-muted m-b-0 m-t-4 text-12" translate>
                    Continue without a subscription. Add one later from the Subscription outbound page.
                  </p>
                </c8y-li-body>
              </c8y-li>

              <!-- By device type -->
              <c8y-li role="listitem">
                <c8y-li-radio name="subscriptionChoice" [value]="'type'" [(ngModel)]="choice"></c8y-li-radio>
                <c8y-li-icon icon="speaker-notes"></c8y-li-icon>
                <c8y-li-body>
                  <strong translate>By device type</strong>
                  <p class="text-muted m-b-0 m-t-4 text-12" translate>
                    All devices of this type will be subscribed automatically.
                  </p>
                  @if (deviceType) {
                    <span class="badge badge--primary m-t-4">{{ deviceType }}</span>
                  } @else {
                    <span class="text-muted text-12" translate>
                      No type detected for this device — type must be available to use this option.
                    </span>
                  }
                </c8y-li-body>
              </c8y-li>

              <!-- By device group -->
              <c8y-li role="listitem">
                <c8y-li-radio name="subscriptionChoice" [value]="'group'" [(ngModel)]="choice"></c8y-li-radio>
                <c8y-li-icon icon="c8y-group"></c8y-li-icon>
                <c8y-li-body>
                  <strong translate>By device group</strong>
                  <p class="text-muted m-b-0 m-t-4 text-12" translate>
                    All devices in the selected group will be subscribed automatically.
                  </p>
                  @if (deviceGroups.length === 1) {
                    <span class="badge badge--primary m-t-4">{{ deviceGroups[0].name }}</span>
                  } @else if (deviceGroups.length > 1) {
                    <!-- Nested group picker using c8y-li-radio -->
                    <c8y-list-group class="m-t-8" role="list" (click)="$event.stopPropagation(); choice = 'group'">
                      @for (g of deviceGroups; track g.id) {
                        <c8y-li [dense]="true" role="listitem">
                          <c8y-li-radio name="groupChoice" [value]="g.id"
                            [(ngModel)]="selectedGroupId"
                            (onSelect)="choice = 'group'">
                          </c8y-li-radio>
                          <c8y-li-body>{{ g.name }}</c8y-li-body>
                        </c8y-li>
                      }
                    </c8y-list-group>
                  } @else {
                    <span class="text-muted text-12" translate>
                      No group detected for this device — group must be available to use this option.
                    </span>
                  }
                </c8y-li-body>
              </c8y-li>

            </c8y-list-group>
          </div>
        </div>
        <div class="card-footer separator p-24 text-center flex-no-shrink">
            <button class="btn btn-default" (click)="onCancel()" translate>Cancel</button>
            <button class="btn btn-primary m-l-8"
            [disabled]="submitting || (choice === 'group' && !selectedGroupId)"
            (click)="onConfirm()" translate>Continue</button>
        </div>
      </div>
    </div>
  `
})
export class SubscriptionChoiceDrawerComponent implements AfterViewInit {
  @Input() deviceType: string | null = null;

  private _deviceGroups: DeviceGroupInfo[] = [];
  get deviceGroups(): DeviceGroupInfo[] { return this._deviceGroups; }
  @Input() set deviceGroups(value: DeviceGroupInfo[]) {
    this._deviceGroups = value;
    if (value.length === 1 && this.selectedGroupId === null) {
      this.selectedGroupId = value[0].id;
    }
  }

  choice: SubscriptionChoice = 'skip';
  selectedGroupId: string | null = null;
  submitting = false;

  private readonly bottomDrawerRef = inject(BottomDrawerRef);
  private readonly subscriptionService = inject(SubscriptionService);
  private readonly alertService = inject(AlertService);
  private readonly cdr = inject(ChangeDetectorRef);

  ngAfterViewInit(): void {
    // c8y-li-radio doesn't reflect the initial ngModel value on first render;
    // detectChanges() forces a write cycle so 'skip' appears pre-selected.
    this.cdr.detectChanges();
  }

  private _resolve: (value: SubscriptionChoice | null) => void;
  result = new Promise<SubscriptionChoice | null>(resolve => {
    this._resolve = resolve;
  });

  onCancel(): void {
    this._resolve(null);
    this.bottomDrawerRef.close();
  }

  async onConfirm(): Promise<void> {
    if (this.choice === 'skip') {
      this._resolve('skip');
      this.bottomDrawerRef.close();
      return;
    }

    this.submitting = true;
    try {
      if (this.choice === 'type') {
        if (!this.deviceType) {
          this.alertService.warning('No device type available for this device.');
          this.submitting = false;
          return;
        }
        // Load existing types and merge so we don't overwrite them
        const existing = await this.subscriptionService.getSubscriptionByDeviceType();
        const existingTypes: string[] = existing?.types ?? [];
        const mergedTypes = Array.from(new Set([...existingTypes, this.deviceType]));
        await this.subscriptionService.updateSubscriptionByDeviceType({
          api: API.ALL.name,
          types: mergedTypes
        });
        this.alertService.info('Subscription request submitted. Subscriptions are processed asynchronously.');
        this._resolve('type');
      } else if (this.choice === 'group' && this.selectedGroupId) {
        const group = this.deviceGroups.find(g => g.id === this.selectedGroupId);
        // Load existing group subscriptions and merge so we don't remove others
        const existingGroups = await this.subscriptionService.getSubscriptionByDeviceGroup();
        const existingDevices: Device[] = existingGroups?.devices ?? [];
        const alreadySubscribed = existingDevices.some(d => d.id === this.selectedGroupId);
        const mergedDevices: Device[] = alreadySubscribed
          ? existingDevices
          : [...existingDevices, { id: this.selectedGroupId, name: group?.name } as Device];
        await this.subscriptionService.updateSubscriptionByDeviceGroup({
          api: API.ALL.name,
          devices: mergedDevices
        });
        this.alertService.info('Subscription request submitted. Subscriptions are processed asynchronously.');
        this._resolve('group');
      }
      this.bottomDrawerRef.close();
    } catch (e: any) {
      this.alertService.danger('Failed to create subscription: ' + e?.message);
    } finally {
      this.submitting = false;
    }
  }
}

