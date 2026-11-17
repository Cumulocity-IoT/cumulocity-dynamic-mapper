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
import {
  Device,
  NotificationSubscriptionResponse,
  SubscriptionStatus
} from '../../shared/mapping/notification-subscription.model';
import { SubscriptionService } from './subscription.service';
import {
  addedTypes,
  changedDevices,
  changedGroups,
  changedTypes,
  OutboundSubscriptionSelection,
  pendingResyncTypes,
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
   * Writes what changed. The write endpoints replace the whole list, so the user's change is
   * rebased onto the CURRENT server lists (added and removed entries relative to the baseline the
   * step loaded) instead of sending the possibly stale list the step shows: somebody else's
   * subscriptions added meanwhile survive. If the current lists cannot be read nothing is written.
   *
   * A list that was written becomes the selection's baseline (the host's object is updated in
   * place), so a retry after a partial failure writes only what is left. A resync that failed
   * stays ticked in `resyncTypes` and is retried. Each write is independent: a failure is
   * reported and the others still run, so one rejected list does not discard the rest.
   */
  async apply(selection: OutboundSubscriptionSelection): Promise<ApplySubscriptionResult> {
    const result: ApplySubscriptionResult = { applied: false, failed: false };
    const typesChanged = changedTypes(selection);
    const groupsChanged = changedGroups(selection);
    const devicesChanged = changedDevices(selection);
    // Only types that are new in this selection can be resynced, and only the ones ticked. Prune
    // the ticks now: once the type is written it is no longer "added", and a resync that fails
    // must stay ticked so a retry runs it again.
    if (typesChanged) {
      const added = addedTypes(selection);
      selection.resyncTypes = selection.resyncTypes.filter(t => added.includes(t));
    }
    const resyncPending = pendingResyncTypes(selection);
    if (!typesChanged && !groupsChanged && !devicesChanged && resyncPending.length === 0) return result;
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
    // editor is open: rebase onto what is on the server now.
    let current: SubscriptionBaseline | undefined;
    if (typesChanged || groupsChanged || devicesChanged) {
      try {
        current = await this.loadBaseline();
        if (!current.available) throw new Error('Outbound mapping is disabled');
      } catch (error) {
        fail(error);
        return result;
      }
    }
    const before = selection.baseline;
    if (current && this.differsFrom(current, before, typesChanged, groupsChanged, devicesChanged)) {
      this.alertService.info(
        gettext('The subscriptions were changed elsewhere while this mapping was open. Your changes were applied on top of them.')
      );
    }

    let typesFailed = false;
    if (typesChanged) {
      try {
        const types = rebaseStrings(current!.types, before.types, selection.types!);
        this.assertWritten(
          await this.subscriptionService.updateSubscriptionByDeviceType({ api: API.ALL.name, types })
        );
        result.applied = true;
        // Written: a retry must not rewrite it. The ticked resyncs stay in resyncTypes until done.
        selection.baseline = { ...selection.baseline!, types: [...types] };
        selection.types = [...types];
      } catch (error) {
        typesFailed = true;
        fail(error);
      }
    }
    // After a successful type write, or on a retry where only the resync is left. Never after a failed write.
    if (!typesFailed) {
      // One after another: each call starts a background rescan of the inventory.
      for (const type of resyncPending.filter(t => (selection.types ?? []).includes(t))) {
        try {
          await this.subscriptionService.resyncTypeSubscription(type);
          result.applied = true;
          selection.resyncTypes = selection.resyncTypes.filter(t => t !== type);
        } catch (error) {
          fail(error);
        }
      }
    }
    if (groupsChanged) {
      try {
        const groups = rebaseById(current!.groups, before.groups, selection.groups!);
        this.assertWritten(
          await this.subscriptionService.updateSubscriptionByDeviceGroup({ api: API.ALL.name, devices: groups })
        );
        result.applied = true;
        selection.baseline = { ...selection.baseline!, groups: [...groups] };
        selection.groups = [...groups];
      } catch (error) {
        fail(error);
      }
    }
    if (devicesChanged) {
      try {
        const devices = rebaseById(current!.devices, before.devices, selection.devices!);
        this.assertWritten(
          await this.subscriptionService.updateSubscriptionDevice(
            { api: API.ALL.name, devices: devices as Device[] },
            this.subscriptionService.STATIC_DEVICE_SUBSCRIPTION
          )
        );
        result.applied = true;
        selection.baseline = { ...selection.baseline!, devices: [...devices] };
        selection.devices = [...devices];
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

  /** A write that came back ERROR (devices that could not be subscribed) is a failure, not a success. */
  private assertWritten(response: NotificationSubscriptionResponse | null | undefined): void {
    if (response?.status !== SubscriptionStatus.ERROR) return;
    const failed = response.failedDeviceIds?.length
      ? ` (${response.failedDeviceIds.length}: ${response.failedDeviceIds.join(', ')})`
      : '';
    throw new Error(this.translateService.instant(gettext('Some subscriptions could not be created')) + failed);
  }

  private differsFrom(
    current: SubscriptionBaseline,
    before: SubscriptionBaseline,
    types: boolean,
    groups: boolean,
    devices: boolean
  ): boolean {
    return (
      (types && !sameStrings(current.types, before.types)) ||
      (groups && !sameStrings(current.groups.map(g => g.id), before.groups.map(g => g.id))) ||
      (devices && !sameStrings(current.devices.map(d => d.id), before.devices.map(d => d.id)))
    );
  }
}

function sameStrings(a: string[], b: string[]): boolean {
  return a.length === b.length && b.every(x => a.includes(x));
}

/** current - (before - wanted) + (wanted - before): the user's additions and removals on top of `current`. */
export function rebaseStrings(current: string[], before: string[], wanted: string[]): string[] {
  const removed = new Set(before.filter(x => !wanted.includes(x)));
  const added = wanted.filter(x => !before.includes(x));
  return [...new Set([...current.filter(x => !removed.has(x)), ...added])];
}

export function rebaseById<T extends { id: string }>(current: T[], before: T[], wanted: T[]): T[] {
  const wantedIds = new Set(wanted.map(d => d.id));
  const beforeIds = new Set(before.map(d => d.id));
  const removed = new Set([...beforeIds].filter(id => !wantedIds.has(id)));
  const kept = current.filter(d => !removed.has(d.id));
  const keptIds = new Set(kept.map(d => d.id));
  return [...kept, ...wanted.filter(d => !beforeIds.has(d.id) && !keptIds.has(d.id))];
}
