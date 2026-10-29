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

import { AlertService } from '@c8y/ngx-components';
import { InventoryService } from '@c8y/client';
import { TestingDeviceService } from './testing-device.service';

/**
 * The Test device page lists the devices carrying the fragment d11r_testDevice (created by a Live Test).
 */
describe('TestingDeviceService', () => {
  let inventory: jasmine.SpyObj<InventoryService>;
  let alert: jasmine.SpyObj<AlertService>;
  let service: TestingDeviceService;

  beforeEach(() => {
    inventory = jasmine.createSpyObj('InventoryService', ['list', 'delete']);
    alert = jasmine.createSpyObj('AlertService', ['success', 'danger']);
    // constructor: (inventoryService, sharedService, alert); only the inventory and the alert are used here
    service = new (TestingDeviceService as any)(inventory, undefined, alert);
  });

  describe('getTotal', () => {
    it('counts the test devices only, not every managed object of the tenant', async () => {
      inventory.list.and.resolveTo({ paging: { totalPages: 3 } } as any);

      const total = await service.getTotal();

      expect(total).toBe(3);
      const filters = inventory.list.calls.mostRecent().args[0] as any;
      expect(filters.query).toContain('d11r_testDevice');
      expect(filters.pageSize).toBe(1);
    });
  });

  describe('deleting test devices', () => {
    let refreshed: number;

    beforeEach(() => {
      refreshed = 0;
      service.refreshData$.subscribe(() => refreshed++);
    });

    it('reports success after the deletes have finished and refreshes the grid', async () => {
      inventory.delete.and.resolveTo({} as any);

      await service.onItemsDelete(['1', '2']);

      expect(inventory.delete).toHaveBeenCalledTimes(2);
      expect(alert.success).toHaveBeenCalledWith('2 test devices deleted.');
      expect(refreshed).toBe(1);
    });

    it('a single row delete says so', async () => {
      inventory.delete.and.resolveTo({} as any);

      await service.onItemDelete({ id: '7' });

      expect(alert.success).toHaveBeenCalledWith('Test device deleted.');
    });

    it('reports a failure, still deletes the others and still refreshes', async () => {
      inventory.delete.and.callFake(((id: string) =>
        id === '2' ? Promise.reject(new Error('forbidden')) : Promise.resolve({})) as any);

      await service.onItemsDelete(['1', '2', '3']);

      expect(inventory.delete).toHaveBeenCalledTimes(3);
      expect(alert.success).not.toHaveBeenCalled();
      expect(alert.danger).toHaveBeenCalledWith('1 of 3 test devices could not be deleted: forbidden');
      expect(refreshed).toBe(1);
    });
  });
});
