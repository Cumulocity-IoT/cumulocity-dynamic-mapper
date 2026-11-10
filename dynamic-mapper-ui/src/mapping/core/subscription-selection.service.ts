/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

import { Injectable, inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { AlertService } from '@c8y/ngx-components';
import { gettext } from '@c8y/ngx-components/gettext';
import { ALERT_INFO_TIMEOUT, API } from '../../shared';
import { Device } from '../../shared/mapping/notification-subscription.model';
import { SubscriptionService } from './subscription.service';
import {
  addedTypes,
  changedDevices,
  changedGroups,
  changedTypes,
  OutboundSubscriptionSelection,
  SubscriptionBaseline
} from '../step-subscription/outbound-subscription-selection.model';

export interface ApplySubscriptionResult {
  /** Anything was written. */
  applied: boolean;
  /** At least one write failed (already alerted); the caller can offer a retry. */
  failed: boolean;
}

/**
 * Loads the subscriptions an outbound mapping's Subscription step starts from, and writes the
 * step's selection once the mapping is saved. The Subscription outbound page edits the same
 * tenant-wide data, so both go through {@link SubscriptionService}.
 */
@Injectable({ providedIn: 'root' })
export class SubscriptionSelectionService {
  private readonly subscriptionService = inject(SubscriptionService);
  private readonly alertService = inject(AlertService);
  private readonly translateService = inject(TranslateService);

  /** The full lists: the selector drawers re-commit the complete desired set, so a page is not enough. */
  async loadBaseline(): Promise<SubscriptionBaseline> {
    const [types, groups, devices] = await Promise.all([
      this.subscriptionService.getSubscriptionByDeviceType(),
      this.subscriptionService.getSubscriptionByDeviceGroup(),
      this.subscriptionService.getSubscriptionDevice(this.subscriptionService.STATIC_DEVICE_SUBSCRIPTION)
    ]);
    return {
      // getSubscriptionDevice answers null when outbound mappings are disabled on the service.
      available: devices !== null,
      types: types?.types ?? [],
      groups: groups?.devices ?? [],
      devices: devices?.devices ?? []
    };
  }

  /**
   * Writes what changed. A list that was written becomes the selection's baseline (the host's
   * object is updated in place), so a retry after a partial failure writes only what is left.
   * Each write is independent: a failure is reported and the others still
   * run, so one rejected list does not discard the rest of the user's selection.
   */
  async apply(selection: OutboundSubscriptionSelection): Promise<ApplySubscriptionResult> {
    const result: ApplySubscriptionResult = { applied: false, failed: false };
    const typesChanged = changedTypes(selection);
    const groupsChanged = changedGroups(selection);
    const devicesChanged = changedDevices(selection);
    if (!typesChanged && !groupsChanged && !devicesChanged) return result;
    // Without a loaded baseline nothing here can be diffed or recorded; the step never allows editing then.
    selection.baseline ??= { available: true, types: [], groups: [], devices: [] };

    const fail = (error: unknown) => {
      result.failed = true;
      const message = error instanceof Error ? error.message : String(error);
      this.alertService.warning(
        this.translateService.instant(gettext('The mapping was saved, but updating the subscriptions failed:')) + ' ' + message
      );
    };

    // Subscriptions are tenant-wide and the Subscription outbound page can change them while the
    // editor is open. Say so instead of silently replacing somebody else's change.
    await this.warnIfChangedMeanwhile(selection);

    if (typesChanged) {
      try {
        await this.subscriptionService.updateSubscriptionByDeviceType({ api: API.ALL.name, types: selection.types! });
        result.applied = true;
        // Only types that are new in this selection can be resynced, and only the ones ticked.
        const resync = addedTypes(selection).filter(type => selection.resyncTypes.includes(type));
        // Written: a retry (the editor stays open after a partial failure) must not rewrite or resync it.
        selection.baseline = { ...selection.baseline!, types: [...selection.types!] };
        // One after another: each call starts a background rescan of the inventory.
        for (const type of resync) {
          try {
            await this.subscriptionService.resyncTypeSubscription(type);
          } catch (error) {
            fail(error);
          }
        }
      } catch (error) {
        fail(error);
      }
    }
    if (groupsChanged) {
      try {
        await this.subscriptionService.updateSubscriptionByDeviceGroup({ api: API.ALL.name, devices: selection.groups! });
        result.applied = true;
        selection.baseline = { ...selection.baseline!, groups: [...selection.groups!] };
      } catch (error) {
        fail(error);
      }
    }
    if (devicesChanged) {
      try {
        await this.subscriptionService.updateSubscriptionDevice(
          { api: API.ALL.name, devices: selection.devices! as Device[] },
          this.subscriptionService.STATIC_DEVICE_SUBSCRIPTION
        );
        result.applied = true;
        selection.baseline = { ...selection.baseline!, devices: [...selection.devices!] };
      } catch (error) {
        fail(error);
      }
    }

    if (result.applied && !result.failed) {
      this.alertService.add({
        text: gettext('Subscription request submitted. Subscriptions are processed asynchronously.'),
        type: 'info',
        timeout: ALERT_INFO_TIMEOUT
      });
    }
    return result;
  }

  private async warnIfChangedMeanwhile(selection: OutboundSubscriptionSelection): Promise<void> {
    if (!selection.baseline) return;
    try {
      const current = await this.loadBaseline();
      const differs =
        (changedTypes(selection) && !sameSet(current.types, selection.baseline.types)) ||
        (changedGroups(selection) && !sameSet(current.groups.map(g => g.id), selection.baseline.groups.map(g => g.id))) ||
        (changedDevices(selection) && !sameSet(current.devices.map(d => d.id), selection.baseline.devices.map(d => d.id)));
      if (differs) {
        this.alertService.warning(
          gettext('The subscriptions were changed elsewhere while this mapping was open. Your selection replaces them.')
        );
      }
    } catch {
      // Advisory only: the write below reports its own failure.
    }
  }
}

function sameSet(a: string[], b: string[]): boolean {
  return a.length === b.length && b.every(x => a.includes(x));
}
