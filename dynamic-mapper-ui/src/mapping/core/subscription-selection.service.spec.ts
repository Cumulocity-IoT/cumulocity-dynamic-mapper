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
import { AlertService } from '@c8y/ngx-components';
import { provideTranslateStub } from '../../shared/testing/translate-stub';
import { SubscriptionService } from './subscription.service';
import { SubscriptionSelectionService } from './subscription-selection.service';
import { emptySelection, OutboundSubscriptionSelection } from '../step-subscription/outbound-subscription-selection.model';

describe('SubscriptionSelectionService', () => {
  let service: SubscriptionSelectionService;
  let subs: jasmine.SpyObj<SubscriptionService> & { STATIC_DEVICE_SUBSCRIPTION: string; DYNAMIC_DEVICE_SUBSCRIPTION: string };
  let alerts: jasmine.SpyObj<AlertService>;

  const baseline = { available: true, types: ['a'], groups: [{ id: 'g1' }], devices: [{ id: 'd1' }] };
  const select = (over: Partial<OutboundSubscriptionSelection>): OutboundSubscriptionSelection =>
    ({ ...emptySelection(), baseline, ...over });

  beforeEach(() => {
    subs = jasmine.createSpyObj('SubscriptionService', [
      'getSubscriptionByDeviceType', 'getSubscriptionByDeviceGroup', 'getSubscriptionDevice',
      'updateSubscriptionByDeviceType', 'updateSubscriptionByDeviceGroup', 'updateSubscriptionDevice',
      'resyncTypeSubscription'
    ]) as any;
    (subs as any).STATIC_DEVICE_SUBSCRIPTION = 'STATIC';
    (subs as any).DYNAMIC_DEVICE_SUBSCRIPTION = 'DYNAMIC';
    subs.getSubscriptionByDeviceType.and.resolveTo({ types: ['a'] } as any);
    subs.getSubscriptionByDeviceGroup.and.resolveTo({ devices: [{ id: 'g1' }] } as any);
    subs.getSubscriptionDevice.and.resolveTo({ devices: [{ id: 'd1' }] } as any);
    subs.updateSubscriptionByDeviceType.and.resolveTo({} as any);
    subs.updateSubscriptionByDeviceGroup.and.resolveTo({} as any);
    subs.updateSubscriptionDevice.and.resolveTo({} as any);
    subs.resyncTypeSubscription.and.resolveTo();
    alerts = jasmine.createSpyObj('AlertService', ['add', 'warning', 'danger', 'success', 'info']);

    TestBed.configureTestingModule({
      providers: [
        provideTranslateStub(),
        { provide: SubscriptionService, useValue: subs },
        { provide: AlertService, useValue: alerts }
      ]
    });
    service = TestBed.inject(SubscriptionSelectionService);
  });

  describe('loadBaseline', () => {
    it('reads the full lists, with the static device subscription', async () => {
      const result = await service.loadBaseline();
      expect(result).toEqual({ available: true, types: ['a'], groups: [{ id: 'g1' }], devices: [{ id: 'd1' }] } as any);
      expect(subs.getSubscriptionDevice).toHaveBeenCalledOnceWith('STATIC');
    });

    it('reports outbound mappings as unavailable when the service answers null', async () => {
      subs.getSubscriptionDevice.and.resolveTo(null);
      subs.getSubscriptionByDeviceType.and.resolveTo(null);
      subs.getSubscriptionByDeviceGroup.and.resolveTo(null);
      expect(await service.loadBaseline()).toEqual({ available: false, types: [], groups: [], devices: [] });
    });
  });

  describe('apply', () => {
    it('writes nothing and shows nothing for an untouched or skipped selection', async () => {
      expect(await service.apply(select({ mode: 'dynamic' }))).toEqual({ applied: false, failed: false });
      expect(await service.apply(select({ mode: 'skip', types: ['x'] }))).toEqual({ applied: false, failed: false });
      expect(subs.updateSubscriptionByDeviceType).not.toHaveBeenCalled();
      expect(alerts.add).not.toHaveBeenCalled();
    });

    it('writes the complete type list, including removals', async () => {
      const result = await service.apply(select({ mode: 'dynamic', types: ['b'] }));
      expect(subs.updateSubscriptionByDeviceType).toHaveBeenCalledOnceWith({ api: 'ALL', types: ['b'] });
      expect(result).toEqual({ applied: true, failed: false });
      expect(alerts.add).toHaveBeenCalledTimes(1);
    });

    it('writes groups for the dynamic mode and devices to the static subscription for the static mode', async () => {
      await service.apply(select({ mode: 'dynamic', groups: [{ id: 'g2' }] as any }));
      expect(subs.updateSubscriptionByDeviceGroup).toHaveBeenCalledOnceWith({ api: 'ALL', devices: [{ id: 'g2' }] as any });

      await service.apply(select({ mode: 'static', devices: [{ id: 'd2' }] as any }));
      expect(subs.updateSubscriptionDevice).toHaveBeenCalledOnceWith({ api: 'ALL', devices: [{ id: 'd2' }] as any }, 'STATIC');
    });

    it('resyncs only the added types that are ticked, one after another', async () => {
      await service.apply(select({ mode: 'dynamic', types: ['a', 'b', 'c'], resyncTypes: ['b', 'c'] }));
      expect(subs.resyncTypeSubscription.calls.allArgs()).toEqual([['b'], ['c']]);

      subs.resyncTypeSubscription.calls.reset();
      await service.apply(select({ mode: 'dynamic', types: ['a', 'd'], resyncTypes: [] }));
      expect(subs.resyncTypeSubscription).not.toHaveBeenCalled();

      // a type that is new but not ticked is not resynced; a ticked type that already existed is ignored
      await service.apply(select({ mode: 'dynamic', types: ['a', 'b', 'c'], resyncTypes: ['c', 'a'] }));
      expect(subs.resyncTypeSubscription.calls.allArgs()).toEqual([['c']]);
    });

    it('does not resync for a type that was only removed', async () => {
      await service.apply(select({ mode: 'dynamic', types: [], resyncTypes: ['b'] }));
      expect(subs.resyncTypeSubscription).not.toHaveBeenCalled();
    });

    it('warns about a failed write, still runs the other writes, and does not claim success', async () => {
      subs.updateSubscriptionByDeviceType.and.rejectWith(new Error('nope'));
      const result = await service.apply(select({ mode: 'dynamic', types: ['b'], groups: [{ id: 'g2' }] as any }));

      expect(result).toEqual({ applied: true, failed: true });
      expect(subs.updateSubscriptionByDeviceGroup).toHaveBeenCalled();
      expect(alerts.warning).toHaveBeenCalledWith(jasmine.stringContaining('nope'));
      expect(alerts.add).not.toHaveBeenCalled();
    });

    it('on retry after a partial failure writes and resyncs only what is left', async () => {
      subs.updateSubscriptionByDeviceGroup.and.rejectWith(new Error('nope'));
      const selection = select({ mode: 'dynamic', types: ['a', 'b'], groups: [{ id: 'g2' }] as any, resyncTypes: ['b'] });
      await service.apply(selection);
      expect(subs.resyncTypeSubscription).toHaveBeenCalledTimes(1);

      subs.getSubscriptionByDeviceType.and.resolveTo({ types: ['a', 'b'] } as any);
      subs.updateSubscriptionByDeviceGroup.and.resolveTo({} as any);
      subs.updateSubscriptionByDeviceType.calls.reset();
      alerts.warning.calls.reset();
      const retry = await service.apply(selection);

      expect(subs.updateSubscriptionByDeviceType).not.toHaveBeenCalled();
      expect(subs.resyncTypeSubscription).toHaveBeenCalledTimes(1);
      expect(subs.updateSubscriptionByDeviceGroup).toHaveBeenCalledTimes(2);
      expect(retry.failed).toBe(false);
      expect(alerts.warning).not.toHaveBeenCalled();
    });

    it('does not resync when the type write failed', async () => {
      subs.updateSubscriptionByDeviceType.and.rejectWith(new Error('nope'));
      await service.apply(select({ mode: 'dynamic', types: ['b'], resyncTypes: ['b'] }));
      expect(subs.resyncTypeSubscription).not.toHaveBeenCalled();
    });

    it('warns when the server changed while the mapping was open, and still writes the selection', async () => {
      subs.getSubscriptionByDeviceType.and.resolveTo({ types: ['a', 'zzz'] } as any);
      await service.apply(select({ mode: 'dynamic', types: ['b'] }));
      expect(alerts.warning).toHaveBeenCalledWith(jasmine.stringContaining('changed elsewhere'));
      expect(subs.updateSubscriptionByDeviceType).toHaveBeenCalled();
    });
  });
});
