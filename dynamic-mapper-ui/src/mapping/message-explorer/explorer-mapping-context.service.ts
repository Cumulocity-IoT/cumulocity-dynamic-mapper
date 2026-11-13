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
import { inject, Injectable } from '@angular/core';
import { IdentityService, InventoryService } from '@c8y/client';
import { SubscriptionSuggestions } from '../step-subscription/outbound-subscription-selection.model';

/** Cumulocity REST path of each API a captured outbound message can address. */
const API_C8Y_PATH: Readonly<Record<string, string>> = {
  ALARM: '/alarm/alarms',
  EVENT: '/event/events',
  MEASUREMENT: '/measurement/measurements',
  INVENTORY: '/inventory/managedObjects',
  OPERATION: '/devicecontrol/operations'
};

/**
 * Looks up what the mapping editor is pre-filled with when a mapping is created from a captured Message Explorer
 * message: the source device's type and groups, and the publish topics of an outbound mapping. Every lookup is
 * best effort. A failure leaves the suggestion out and never blocks the mapping from being created.
 */
@Injectable({ providedIn: 'root' })
export class ExplorerMappingContextService {
  private readonly inventoryService = inject(InventoryService);
  private readonly identityService = inject(IdentityService);

  /** The API an outbound message addresses, from its topic (`EVENT/CREATE` and `EVENT_WITH_CHILDREN/..` give `EVENT`). */
  inferTargetAPIFromTopic(topic: string | undefined): string | undefined {
    if (!topic) return undefined;
    const segment = topic.split('/')[0].toUpperCase();
    for (const api of Object.keys(API_C8Y_PATH)) {
      if (segment === api || segment.startsWith(api + '_')) {
        return api;
      }
    }
    return undefined;
  }

  /**
   * The source device's type and groups, offered as one-click choices in the mapping's Subscription step.
   * Nothing is subscribed here. Returns `undefined` when there is nothing to offer.
   *
   * @param sourceId source of the message, if the message carries one
   * @param sessionSourceId source the session was started for, used when the message has none
   * @param sessionDeviceType type fetched when the session started. It is reused only when the message came from
   *   exactly that source: when the session source is a group, messages come from its member devices, and the
   *   session's type would be the group's own (for example `c8y_DeviceGroup`), so the device is fetched again.
   */
  async resolveSubscriptionSuggestions(
    sourceId: string | undefined | null,
    sessionSourceId: string | null,
    sessionDeviceType: string | null
  ): Promise<SubscriptionSuggestions | undefined> {
    const effectiveDeviceId = sourceId ?? sessionSourceId;
    let deviceType: string | null =
      effectiveDeviceId && effectiveDeviceId === sessionSourceId ? sessionDeviceType : null;
    let deviceName: string | undefined;
    let groups: { id: string; name: string }[] = [];
    if (effectiveDeviceId) {
      try {
        const { data } = await this.inventoryService.detail(effectiveDeviceId, { withParents: true });
        deviceName = data['name'];
        if (deviceType === null) {
          deviceType = data['type'] ?? null;
        }
        const parentIds: string[] = (data.assetParents?.references ?? []).map((ref) =>
          String(ref.managedObject.id)
        );
        groups = await Promise.all(
          parentIds.map(async id => {
            try {
              const { data: groupData } = await this.inventoryService.detail(id);
              return { id, name: groupData['name'] ?? id };
            } catch {
              return { id, name: id };
            }
          })
        );
      } catch {
        // non-fatal: the step then offers no suggestions
      }
    }
    if (!effectiveDeviceId && !deviceType && groups.length === 0) {
      return undefined;
    }
    return {
      deviceId: effectiveDeviceId ?? undefined,
      deviceName,
      deviceType: deviceType ?? undefined,
      groups
    };
  }

  /**
   * The publish topic and a sample topic for an outbound mapping that addresses `targetAPI`. The sample uses the
   * source device's `c8y_Serial` external ID when it can be read, and the text `externalId` otherwise.
   */
  async resolvePublishTopics(
    targetAPI: string | undefined,
    sourceId: string | undefined | null
  ): Promise<{ publishTopic?: string; publishTopicSample?: string }> {
    const apiPath = targetAPI ? API_C8Y_PATH[targetAPI] : undefined;
    if (!apiPath) {
      return {};
    }
    let externalIdLabel = 'externalId';
    if (sourceId) {
      try {
        const { data: extIds } = await this.identityService.list(sourceId);
        const serial = extIds.find((e) => e.type === 'c8y_Serial');
        if (serial?.externalId) {
          externalIdLabel = serial.externalId;
        }
      } catch {
        // non-fatal: keep the default label
      }
    }
    return { publishTopic: apiPath + '/#', publishTopicSample: apiPath + '/' + externalIdLabel };
  }
}
