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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { AlertService, DataGridComponent } from '@c8y/ngx-components';
import { of } from 'rxjs';
import { MappingSubscriptionComponent } from './subscription.component';
import { MappingService } from '../core/mapping.service';
import { SubscriptionService } from '../core/subscription.service';
import { SharedService } from '../../shared';
import { ConfirmationModalService } from '../../shared/service/confirmation-modal.service';
import { Direction, Feature } from '../../shared';
import { Device, NotificationSubscriptionResponse } from '../shared/mapping.model';
import { IIdentified } from '@c8y/client';

describe('MappingSubscriptionComponent', () => {
  let component: MappingSubscriptionComponent;
  let fixture: ComponentFixture<MappingSubscriptionComponent>;
  let mockMappingService: jasmine.SpyObj<MappingService>;
  let mockSubscriptionService: jasmine.SpyObj<SubscriptionService>;
  let mockSharedService: jasmine.SpyObj<SharedService>;
  let mockAlertService: jasmine.SpyObj<AlertService>;
  let mockConfirmationService: jasmine.SpyObj<ConfirmationModalService>;
  let mockRouter: jasmine.SpyObj<Router>;

  const mockFeature: Feature = {
    outputMappingEnabled: true,
    externalExtensionsEnabled: true,
    userHasMappingAdminRole: true,
    userHasMappingCreateRole: true,
    pulsarAvailable: false,
    deviceIsolationMQTTServiceEnabled: false
  };

  const mockDevices: Device[] = [
    { id: '1', name: 'Device 1', type: 'c8y_Device' },
    { id: '2', name: 'Device 2', type: 'c8y_Sensor' }
  ];

  const mockSubscriptionResponse: NotificationSubscriptionResponse = {
    api: 'ALL',
    subscriptionName: 'test-subscription',
    devices: mockDevices,
    subscriptionId: 'sub-123',
    status: 'ACTIVE' as any
  };

  beforeEach(async () => {
    // Create spy objects
    mockMappingService = jasmine.createSpyObj('MappingService', [
      'refreshMappings',
      'stopChangedMappingEvents'
    ]);

    mockSubscriptionService = jasmine.createSpyObj('SubscriptionService', [
      'getSubscriptionDevice',
      'getSubscriptionByDeviceGroup',
      'getSubscriptionByDeviceType',
      'updateSubscriptionDevice',
      'updateSubscriptionByDeviceGroup',
      'updateSubscriptionByDeviceType',
      'deleteSubscriptionDevice'
    ], {
      STATIC_DEVICE_SUBSCRIPTION: 'static-sub',
      DYNAMIC_DEVICE_SUBSCRIPTION: 'dynamic-sub'
    });

    mockSharedService = jasmine.createSpyObj('SharedService', [
      'runOperation'
    ]);

    mockAlertService = jasmine.createSpyObj('AlertService', [
      'success',
      'danger'
    ]);

    mockConfirmationService = jasmine.createSpyObj('ConfirmationModalService', [
      'confirmDeletion'
    ]);

    mockRouter = jasmine.createSpyObj('Router', ['navigate'], {
      url: '/c8y-pkg-dynamic-mapper/node1/mappings/subscription/static'
    });

    // Setup default return values
    mockSubscriptionService.getSubscriptionDevice.and.returnValue(
      Promise.resolve(mockSubscriptionResponse)
    );
    mockSubscriptionService.getSubscriptionByDeviceGroup.and.returnValue(
      Promise.resolve(mockSubscriptionResponse)
    );
    mockSubscriptionService.getSubscriptionByDeviceType.and.returnValue(
      Promise.resolve({ types: ['c8y_Device', 'c8y_Sensor'] } as any)
    );
    mockSharedService.runOperation.and.returnValue(
      Promise.resolve({ status: 200 } as any)
    );

    await TestBed.configureTestingModule({
      imports: [MappingSubscriptionComponent],
      providers: [
        { provide: MappingService, useValue: mockMappingService },
        { provide: SubscriptionService, useValue: mockSubscriptionService },
        { provide: SharedService, useValue: mockSharedService },
        { provide: AlertService, useValue: mockAlertService },
        { provide: ConfirmationModalService, useValue: mockConfirmationService },
        { provide: Router, useValue: mockRouter },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              data: { feature: mockFeature }
            }
          }
        }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(MappingSubscriptionComponent);
    component = fixture.componentInstance;
  });

  describe('Component Initialization', () => {
    it('should create the component', () => {
      expect(component).toBeTruthy();
    });

    it('should parse path from router URL', async () => {
      await component.ngOnInit();
      expect(component.path).toBe('static');
      expect(component.titleSubscription).toContain('static');
    });

    it('should parse dynamic path from router URL', async () => {
      // Override the router URL property
      Object.defineProperty(mockRouter, 'url', {
        get: () => '/c8y-pkg-dynamic-mapper/node1/mappings/subscription/dynamic',
        configurable: true
      });

      await component.ngOnInit();
      expect(component.path).toBe('dynamic');
    });

    it('should load all subscriptions in parallel', async () => {
      await component.ngOnInit();

      expect(mockSubscriptionService.getSubscriptionDevice).toHaveBeenCalled();
      expect(mockSubscriptionService.getSubscriptionByDeviceGroup).toHaveBeenCalled();
      expect(mockSubscriptionService.getSubscriptionByDeviceType).toHaveBeenCalled();
    });

    it('should setup action controls for admin users', async () => {
      await component.ngOnInit();

      expect(component.actionControlSubscription.length).toBe(1);
      expect(component.bulkActionControlSubscription.length).toBe(1);
    });

    it('should not setup action controls for non-admin users', async () => {
      const nonAdminFeature: Feature = {
        ...mockFeature,
        userHasMappingAdminRole: false,
        userHasMappingCreateRole: false
      };

      TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [MappingSubscriptionComponent],
        providers: [
          { provide: MappingService, useValue: mockMappingService },
          { provide: SubscriptionService, useValue: mockSubscriptionService },
          { provide: SharedService, useValue: mockSharedService },
          { provide: AlertService, useValue: mockAlertService },
          { provide: ConfirmationModalService, useValue: mockConfirmationService },
          { provide: Router, useValue: mockRouter },
          {
            provide: ActivatedRoute,
            useValue: {
              snapshot: {
                data: { feature: nonAdminFeature }
              }
            }
          }
        ]
      }).compileComponents();

      const newFixture = TestBed.createComponent(MappingSubscriptionComponent);
      const newComponent = newFixture.componentInstance;

      await newComponent.ngOnInit();

      expect(newComponent.actionControlSubscription.length).toBe(0);
      expect(newComponent.bulkActionControlSubscription.length).toBe(0);
    });
  });

  describe('Subscription Loading', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('should load device subscriptions', async () => {
      await component.loadSubscriptionDevice();

      expect(mockSubscriptionService.getSubscriptionDevice).toHaveBeenCalled();
      expect(component.subscriptionDevices).toEqual(mockSubscriptionResponse);
      expect(component.subscribedDevices).toEqual(mockDevices);
    });

    it('should load device group subscriptions', async () => {
      await component.loadSubscriptionByDeviceGroup();

      expect(mockSubscriptionService.getSubscriptionByDeviceGroup).toHaveBeenCalled();
      expect(component.subscriptionDeviceGroups).toEqual(mockSubscriptionResponse);
      expect(component.subscribedDeviceGroups).toEqual(mockDevices);
    });

    it('should load device type subscriptions', async () => {
      await component.loadSubscriptionByDeviceType();

      expect(mockSubscriptionService.getSubscriptionByDeviceType).toHaveBeenCalled();
      expect(component.subscribedDeviceTypes).toEqual(['c8y_Device', 'c8y_Sensor']);
    });

    it('should use static subscription path', async () => {
      component.path = 'static';
      await component.loadSubscriptionDevice();

      expect(mockSubscriptionService.getSubscriptionDevice).toHaveBeenCalledWith(
        mockSubscriptionService.STATIC_DEVICE_SUBSCRIPTION
      );
    });

    it('should use dynamic subscription path', async () => {
      component.path = 'dynamic';
      await component.loadSubscriptionDevice();

      expect(mockSubscriptionService.getSubscriptionDevice).toHaveBeenCalledWith(
        mockSubscriptionService.DYNAMIC_DEVICE_SUBSCRIPTION
      );
    });
  });

  describe('Subscription Deletion', () => {
    const mockDevice: IIdentified = { id: '1', name: 'Test Device' } as any;

    beforeEach(async () => {
      await component.ngOnInit();
      mockSubscriptionService.deleteSubscriptionDevice.and.returnValue(Promise.resolve());
    });

    it('should delete subscription successfully', async () => {
      await component.deleteSubscription(mockDevice);

      expect(mockSubscriptionService.deleteSubscriptionDevice).toHaveBeenCalledWith(
        mockDevice,
        mockSubscriptionService.STATIC_DEVICE_SUBSCRIPTION
      );
      expect(mockAlertService.success).toHaveBeenCalled();
    });

    it('should handle deletion errors', async () => {
      const error = new Error('Deletion failed');
      mockSubscriptionService.deleteSubscriptionDevice.and.returnValue(Promise.reject(error));

      await component.deleteSubscription(mockDevice);

      expect(mockAlertService.danger).toHaveBeenCalled();
    });

    it('should confirm deletion before deleting', async () => {
      mockConfirmationService.confirmDeletion.and.returnValue(Promise.resolve(true));

      const result = await component['deleteSubscriptionWithConfirmation'](mockDevice, true, false);

      expect(mockConfirmationService.confirmDeletion).toHaveBeenCalledWith('subscription', false);
      expect(mockSubscriptionService.deleteSubscriptionDevice).toHaveBeenCalled();
      expect(result).toBe(true);
    });

    it('should not delete if user cancels confirmation', async () => {
      mockConfirmationService.confirmDeletion.and.returnValue(Promise.resolve(false));

      const result = await component['deleteSubscriptionWithConfirmation'](mockDevice, true, false);

      expect(mockConfirmationService.confirmDeletion).toHaveBeenCalled();
      expect(mockSubscriptionService.deleteSubscriptionDevice).not.toHaveBeenCalled();
      expect(result).toBe(false);
    });

    it('should handle bulk deletion', async () => {
      component.subscriptionDevices = {
        ...mockSubscriptionResponse,
        devices: [
          { id: '1', name: 'Device 1' },
          { id: '2', name: 'Device 2' }
        ]
      };

      mockConfirmationService.confirmDeletion.and.returnValue(Promise.resolve(true));
      component.subscriptionGrid = { setAllItemsSelected: jasmine.createSpy() } as any;

      await component['deleteSubscriptionBulkWithConfirmation'](['1', '2']);

      expect(mockConfirmationService.confirmDeletion).toHaveBeenCalledWith('subscription', true);
      expect(mockSubscriptionService.deleteSubscriptionDevice).toHaveBeenCalledTimes(2);
      expect(component.subscriptionGrid.setAllItemsSelected).toHaveBeenCalledWith(false);
    });
  });

  describe('Subscription Updates', () => {
    beforeEach(async () => {
      await component.ngOnInit();
      mockSubscriptionService.updateSubscriptionDevice.and.returnValue(Promise.resolve(mockSubscriptionResponse));
      mockSubscriptionService.updateSubscriptionByDeviceGroup.and.returnValue(Promise.resolve(mockSubscriptionResponse));
      mockSubscriptionService.updateSubscriptionByDeviceType.and.returnValue(Promise.resolve(mockSubscriptionResponse));
    });

    it('should update device subscriptions', async () => {
      await component.onCommitSubscriptionDevice(mockDevices as IIdentified[]);

      expect(mockSubscriptionService.updateSubscriptionDevice).toHaveBeenCalled();
      expect(mockAlertService.success).toHaveBeenCalled();
      expect(component.showConfigSubscription1).toBe(false);
    });

    it('should update device group subscriptions', async () => {
      await component.onCommitSubscriptionByDeviceGroup(mockDevices as IIdentified[]);

      expect(mockSubscriptionService.updateSubscriptionByDeviceGroup).toHaveBeenCalled();
      expect(mockAlertService.success).toHaveBeenCalled();
      expect(component.showConfigSubscription3).toBe(false);
    });

    it('should update device type subscriptions', async () => {
      const types = ['c8y_Device', 'c8y_Sensor'];
      await component.onCommitSubscriptionByDeviceType(types);

      expect(mockSubscriptionService.updateSubscriptionByDeviceType).toHaveBeenCalled();
      expect(mockAlertService.success).toHaveBeenCalled();
      expect(component.showConfigSubscription4).toBe(false);
    });

    it('should handle update errors', async () => {
      const error = new Error('Update failed');
      mockSubscriptionService.updateSubscriptionDevice.and.returnValue(
        Promise.reject(error)
      );

      await component.onCommitSubscriptionDevice(mockDevices as IIdentified[]);

      expect(mockAlertService.danger).toHaveBeenCalled();
    });
  });

  describe('Toggle Visibility', () => {
    it('should toggle subscription config 1', () => {
      expect(component.showConfigSubscription1).toBe(false);
      component.onDefineSubscription1();
      expect(component.showConfigSubscription1).toBe(true);
      component.onDefineSubscription1();
      expect(component.showConfigSubscription1).toBe(false);
    });

    it('should toggle subscription config 2', () => {
      expect(component.showConfigSubscription2).toBe(false);
      component.onDefineSubscription2();
      expect(component.showConfigSubscription2).toBe(true);
    });

    it('should toggle subscription config 3', () => {
      expect(component.showConfigSubscription3).toBe(false);
      component.onDefineSubscription3();
      expect(component.showConfigSubscription3).toBe(true);
    });

    it('should toggle subscription config 4', () => {
      expect(component.showConfigSubscription4).toBe(false);
      component.onDefineSubscription4();
      expect(component.showConfigSubscription4).toBe(true);
    });
  });

  describe('Reload Operations', () => {
    beforeEach(async () => {
      await component.ngOnInit();
    });

    it('should reload mappings successfully', async () => {
      await component.onReload();

      expect(mockSharedService.runOperation).toHaveBeenCalled();
      expect(mockAlertService.success).toHaveBeenCalled();
      expect(component.isConnectionToMQTTEstablished).toBe(true);
    });

    it('should handle reload failure', async () => {
      mockSharedService.runOperation.and.returnValue(
        Promise.resolve({ status: 500 } as any)
      );

      await component.onReload();

      expect(mockAlertService.danger).toHaveBeenCalled();
    });
  });

  describe('Component Cleanup', () => {
    it('should cleanup on destroy', () => {
      spyOn(component['destroy$'], 'next');
      spyOn(component['destroy$'], 'complete');

      component.ngOnDestroy();

      expect(component['destroy$'].next).toHaveBeenCalled();
      expect(component['destroy$'].complete).toHaveBeenCalled();
      expect(mockMappingService.stopChangedMappingEvents).toHaveBeenCalled();
    });
  });
});
