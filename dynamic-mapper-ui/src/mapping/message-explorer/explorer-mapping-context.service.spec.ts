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
import { TestBed } from '@angular/core/testing';
import { IdentityService, InventoryService } from '@c8y/client';
import { ExplorerMappingContextService } from './explorer-mapping-context.service';

describe('ExplorerMappingContextService', () => {
  let service: ExplorerMappingContextService;
  let inventory: jasmine.SpyObj<InventoryService>;
  let identity: jasmine.SpyObj<IdentityService>;

  const detail = (data: unknown) => Promise.resolve({ data } as never);

  beforeEach(() => {
    inventory = jasmine.createSpyObj('InventoryService', ['detail']);
    identity = jasmine.createSpyObj('IdentityService', ['list']);
    TestBed.configureTestingModule({
      providers: [
        { provide: InventoryService, useValue: inventory },
        { provide: IdentityService, useValue: identity }
      ]
    });
    service = TestBed.inject(ExplorerMappingContextService);
  });

  describe('inferTargetAPIFromTopic', () => {
    it('reads the API from the first topic segment', () => {
      expect(service.inferTargetAPIFromTopic('EVENT/CREATE')).toBe('EVENT');
      expect(service.inferTargetAPIFromTopic('alarm/x/y')).toBe('ALARM');
    });

    it('maps the variants with a suffix to their API', () => {
      expect(service.inferTargetAPIFromTopic('EVENT_WITH_CHILDREN/CREATE')).toBe('EVENT');
    });

    it('returns undefined for an unknown API or a missing topic', () => {
      expect(service.inferTargetAPIFromTopic('SOMETHING/ELSE')).toBeUndefined();
      expect(service.inferTargetAPIFromTopic('')).toBeUndefined();
      expect(service.inferTargetAPIFromTopic(undefined)).toBeUndefined();
    });
  });

  describe('resolveSubscriptionSuggestions', () => {
    it('returns nothing when there is no source at all', async () => {
      expect(await service.resolveSubscriptionSuggestions(undefined, null, null)).toBeUndefined();
      expect(inventory.detail).not.toHaveBeenCalled();
    });

    it('reuses the session device type when the message came from the session source', async () => {
      inventory.detail.and.returnValue(detail({ name: 'Pump', type: 'c8y_DeviceGroup' }));

      const result = await service.resolveSubscriptionSuggestions('7', '7', 'pump_type');

      expect(result).toEqual({ deviceId: '7', deviceName: 'Pump', deviceType: 'pump_type', groups: [] });
    });

    it('fetches the type again when the session source is a group and the message came from a member', async () => {
      inventory.detail.and.returnValue(detail({ name: 'Pump 3', type: 'pump_type' }));

      const result = await service.resolveSubscriptionSuggestions('9', '7', 'c8y_DeviceGroup');

      expect(result?.deviceId).toBe('9');
      expect(result?.deviceType).toBe('pump_type');
    });

    it('names the parent groups and falls back to the id when a group cannot be read', async () => {
      inventory.detail.and.callFake((id: string) => {
        if (id === '7') {
          return detail({
            name: 'Pump',
            type: 'pump_type',
            assetParents: { references: [{ managedObject: { id: 1 } }, { managedObject: { id: 2 } }] }
          });
        }
        return id === '1' ? detail({ name: 'Plant A' }) : Promise.reject(new Error('gone'));
      });

      const result = await service.resolveSubscriptionSuggestions('7', null, null);

      expect(result?.groups).toEqual([{ id: '1', name: 'Plant A' }, { id: '2', name: '2' }]);
    });

    it('still returns the source id when the device cannot be read', async () => {
      inventory.detail.and.returnValue(Promise.reject(new Error('404')));

      const result = await service.resolveSubscriptionSuggestions('7', null, null);

      expect(result).toEqual({ deviceId: '7', deviceName: undefined, deviceType: undefined, groups: [] });
    });
  });

  describe('resolvePublishTopics', () => {
    it('returns nothing for an unknown API', async () => {
      expect(await service.resolvePublishTopics(undefined, '7')).toEqual({});
      expect(await service.resolvePublishTopics('NOPE', '7')).toEqual({});
    });

    it('puts the c8y_Serial external id into the sample topic', async () => {
      identity.list.and.returnValue(
        Promise.resolve({ data: [{ type: 'other', externalId: 'x' }, { type: 'c8y_Serial', externalId: 'SN-1' }] } as never)
      );

      expect(await service.resolvePublishTopics('EVENT', '7')).toEqual({
        publishTopic: '/event/events/#',
        publishTopicSample: '/event/events/SN-1'
      });
    });

    it('keeps the placeholder when there is no source or the lookup fails', async () => {
      expect((await service.resolvePublishTopics('ALARM', undefined)).publishTopicSample).toBe('/alarm/alarms/externalId');
      identity.list.and.returnValue(Promise.reject(new Error('500')));
      expect((await service.resolvePublishTopics('ALARM', '7')).publishTopicSample).toBe('/alarm/alarms/externalId');
    });
  });
});
