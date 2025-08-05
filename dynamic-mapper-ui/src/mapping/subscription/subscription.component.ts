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
import {
  Component,
  OnDestroy,
  OnInit,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import {
  ActionControl,
  AlertService,
  BuiltInActionType,
  BulkActionControl,
  Column,
  ColumnDataType,
  DataGridComponent,
  DisplayOptions,
  Pagination,
  gettext
} from '@c8y/ngx-components';
import {
  API,
  ConfirmationModalComponent,
  Direction,
  Feature,
  MappingType,
  Operation
} from '../../shared';

import { ActivatedRoute, Router } from '@angular/router';
import { IIdentified } from '@c8y/client';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { Subject } from 'rxjs';
import { DeploymentMapEntry, SharedService, StepperConfiguration } from '../../shared';
import { MappingService } from '../core/mapping.service';
import { C8YNotificationSubscription, Device } from '../shared/mapping.model';

@Component({
  selector: 'd11r-mapping-subscription-grid',
  templateUrl: 'subscription.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class MappingSubscriptionComponent implements OnInit, OnDestroy {
  @ViewChild('subscriptionGrid') subscriptionGrid: DataGridComponent;

  constructor(
    private mappingService: MappingService,
    private shareService: SharedService,
    private alertService: AlertService,
    private bsModalService: BsModalService,
    private router: Router,
    private route: ActivatedRoute
  ) {
    // console.log('constructor');
    const href = this.router.url;
    this.stepperConfiguration.direction = href.match(
      /c8y-pkg-dynamic-mapper\/node1\/mappings\/inbound/g
    )
      ? Direction.INBOUND
      : Direction.OUTBOUND;

    this.titleMapping = `Mapping ${this.stepperConfiguration.direction.toLowerCase()}`;
    this.loadSubscriptionDevices();
    this.loadSubscriptionDeviceGroups();
  }

  showConfigSubscription: boolean = false;
  showConfigSubscription2: boolean = false;
  showConfigSubscription3: boolean = false;

  isConnectionToMQTTEstablished: boolean;

  subscriptionDevices: C8YNotificationSubscription;
  subscriptionDeviceGroups: C8YNotificationSubscription;
  subscribedDevices: any[];
  subscribedDeviceGroups: any[];
  devices: IIdentified[] = [];
  Direction = Direction;

  stepperConfiguration: StepperConfiguration = {};
  titleMapping: string;
  readonly titleSubscription: string = 'Subscription devices mapping outbound';
  deploymentMapEntry: DeploymentMapEntry;

  readonly displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: false,
    gridHeader: true,
    hover: true
  };

  columnsSubscriptions: Column[] = [
    {
      name: 'id',
      header: 'Device ID',
      path: 'id',
      filterable: false,
      dataType: ColumnDataType.TextShort,
      visible: true
    },
    {
      header: 'Name',
      name: 'name',
      path: 'name',
      filterable: true
    },
    {
      header: 'Type',
      name: 'type',
      path: 'type',
      filterable: true
    }
  ];

  value: string;
  mappingType: MappingType;
  destroy$: Subject<boolean> = new Subject<boolean>();

  readonly pagination: Pagination = {
    pageSize: 30,
    currentPage: 1
  };

  actionControlSubscription: ActionControl[] = [];
  bulkActionControlSubscription: BulkActionControl[] = [];
  feature: Feature;



  ngOnInit(): void {
    this.feature = this.route.snapshot.data['feature'];
    if (this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole) {
      this.bulkActionControlSubscription.push({
        type: BuiltInActionType.Delete,
        callback: this.deleteSubscriptionBulkWithConfirmation.bind(this)
      });
      this.actionControlSubscription.push({
        type: BuiltInActionType.Delete,
        callback: this.deleteSubscriptionWithConfirmation.bind(this)
      });
    }
  }

  async loadSubscriptionDevices(): Promise<void> {
    this.subscriptionDevices = await this.mappingService.getSubscriptionDevice();
    this.subscribedDevices = this.subscriptionDevices.devices;
    this.subscriptionGrid?.reload();
  }

  async loadSubscriptionDeviceGroups(): Promise<void> {
    this.subscriptionDeviceGroups = await this.mappingService.getSubscriptionDeviceGroup();
    this.subscribedDeviceGroups = this.subscriptionDeviceGroups.devices;
    this.subscriptionGrid?.reload();
  }

  onDefineSubscription(): void {
    this.showConfigSubscription = !this.showConfigSubscription;
  }

  onDefineSubscription2(): void {
    this.showConfigSubscription2 = !this.showConfigSubscription2;
  }

  async deleteSubscription(device: IIdentified): Promise<void> {
    // console.log('Delete device', device);
    try {
      await this.mappingService.deleteSubscriptionDevice(device);
      this.alertService.success(
        gettext('Subscription for this device deleted successfully')
      );
      this.loadSubscriptionDevices();
    } catch (error) {
      this.alertService.danger(
        gettext('Failed to delete subscription:') + error
      );
    }
  }

  private async deleteSubscriptionBulkWithConfirmation(ids: string[]): Promise<void> {
    let continueDelete: boolean = false;
    for (let index = 0; index < ids.length; index++) {
      const device2Delete = this.subscriptionDevices?.devices.find(
        (de) => de.id == ids[index]
      );
      if (index == 0) {
        continueDelete = await this.deleteSubscriptionWithConfirmation(
          device2Delete,
          true,
          true
        );
      } else if (continueDelete) {
        this.deleteSubscription(device2Delete);
      }
    }
    this.isConnectionToMQTTEstablished = true;
    this.mappingService.refreshMappings(this.stepperConfiguration.direction);
    this.subscriptionGrid.setAllItemsSelected(false);
  }

  private async deleteSubscriptionWithConfirmation(
    device2Delete: IIdentified,
    confirmation: boolean = true,
    multiple: boolean = false
  ): Promise<boolean | PromiseLike<boolean>> {
    let result: boolean = false;
    if (confirmation) {
      const initialState = {
        title: multiple ? 'Delete subscriptions' : 'Delete subscription',
        message: multiple
          ? 'You are about to delete subscriptions. Do you want to proceed to delete ALL?'
          : 'You are about to delete a subscription. Do you want to proceed?',
        labels: {
          ok: 'Delete',
          cancel: 'Cancel'
        }
      };
      const confirmDeletionModalRef: BsModalRef = this.bsModalService.show(
        ConfirmationModalComponent,
        { initialState }
      );

      result = await confirmDeletionModalRef.content.closeSubject.toPromise();
      if (result) {
        await this.deleteSubscription(device2Delete);
      }
    }
    this.subscriptionGrid.setAllItemsSelected(false);
    return result;
  }

  async onCommitSubscriptions(deviceList: IIdentified[]): Promise<void> {
    this.subscriptionDevices = {
      api: API.ALL.name,
      devices: deviceList as Device[]
    };
    // console.log('Changed deviceList:', this.subscription.devices);
    try {
      await this.mappingService.updateSubscriptionDevice(
        this.subscriptionDevices
      );
      this.loadSubscriptionDevices();
      this.alertService.success(gettext('Subscriptions updated successfully'));
    } catch (error) {
      this.alertService.danger(
        gettext('Failed to update subscriptions:') + error
      );
    }
    this.showConfigSubscription = false;
    this.showConfigSubscription2 = false;
  }

  async onReload(): Promise<void> {
    this.reloadMappingsInBackend();
  }

  private async reloadMappingsInBackend(): Promise<void> {
    const response2 = await this.shareService.runOperation(
      { operation: Operation.RELOAD_MAPPINGS }
    );
    // console.log('Activate mapping response:', response2);
    if (response2.status < 300) {
      this.alertService.success(gettext('Mappings reloaded'));
      this.isConnectionToMQTTEstablished = true;
    } else {
      this.alertService.danger(gettext('Failed to activate mappings'));
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next(true);
    this.destroy$.unsubscribe();
    this.mappingService.stopChangedMappingEvents();
  }
}