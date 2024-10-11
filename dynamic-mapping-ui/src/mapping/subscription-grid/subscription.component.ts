/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
  MappingType,
  Operation
} from '../../shared';

import { Router } from '@angular/router';
import { IIdentified } from '@c8y/client';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { Subject } from 'rxjs';
import { MappingService } from '../core/mapping.service';
import { APIRendererComponent } from '../renderer/api.renderer.component';
import { NameRendererComponent } from '../renderer/name.renderer.component';
// import { QOSRendererComponent } from '../renderer/qos-cell.renderer.component';
import { StatusActivationRendererComponent } from '../renderer/status-activation-renderer.component';
import { StatusRendererComponent } from '../renderer/status-cell.renderer.component';
// import { TemplateRendererComponent } from '../renderer/template.renderer.component';
import { StepperConfiguration } from '../../shared';
import { DeploymentMapEntry } from '../../shared/model/shared.model';
import { SharedService } from '../../shared/shared.service';
import { MappingDeploymentRendererComponent } from '../renderer/mappingDeployment.renderer.component';
import { SnoopedTemplateRendererComponent } from '../renderer/snoopedTemplate.renderer.component';
import { C8YNotificationSubscription } from '../shared/mapping.model';

@Component({
  selector: 'd11r-mapping-subscription-grid',
  templateUrl: 'subscription.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class MappingSubscriptionComponent implements OnInit, OnDestroy {
  @ViewChild('subscriptionGrid') subscriptionGrid: DataGridComponent;

  showConfigSubscription: boolean = false;

  isConnectionToMQTTEstablished: boolean;

  subscription: C8YNotificationSubscription;
  devices: IIdentified[] = [];
  Direction = Direction;

  stepperConfiguration: StepperConfiguration = {};
  titleMapping: string;
  titleSubscription: string = 'Subscription on devices for mapping outbound';
  deploymentMapEntry: DeploymentMapEntry;

  displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: false,
    gridHeader: true,
    hover: true
  };

  columnsMappings: Column[];
  columnsSubscriptions: Column[] = [
    {
      name: 'id',
      header: 'System ID',
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
    }
  ];

  value: string;
  mappingType: MappingType;
  destroy$: Subject<boolean> = new Subject<boolean>();

  pagination: Pagination = {
    pageSize: 30,
    currentPage: 1
  };
  actionControlSubscription: ActionControl[] = [];
  bulkActionControlSubscription: BulkActionControl[] = [];

  constructor(
    public mappingService: MappingService,
    public shareService: SharedService,
    public alertService: AlertService,
    private bsModalService: BsModalService,
    private router: Router
  ) {
    // console.log('constructor');
    const href = this.router.url;
    this.stepperConfiguration.direction = href.match(
      /sag-ps-pkg-dynamic-mapping\/node1\/mappings\/inbound/g
    )
      ? Direction.INBOUND
      : Direction.OUTBOUND;

    this.columnsMappings = this.getColumnsMappings();
    this.titleMapping = `Mapping ${this.stepperConfiguration.direction.toLowerCase()}`;
    this.loadSubscriptions();
  }

  async loadSubscriptions() {
    this.subscription = await this.mappingService.getSubscriptions();
  }

  ngOnInit() {
    // console.log('ngOnInit');

    this.bulkActionControlSubscription.push({
      type: BuiltInActionType.Delete,
      callback: this.deleteSubscriptionBulkWithConfirmation.bind(this)
    });
    this.actionControlSubscription.push({
      type: BuiltInActionType.Delete,
      callback: this.deleteSubscriptionWithConfirmation.bind(this)
    });
  }

  getColumnsMappings(): Column[] {
    const cols: Column[] = [
      {
        name: 'name',
        header: 'Name',
        path: 'mapping.name',
        filterable: false,
        dataType: ColumnDataType.TextShort,
        cellRendererComponent: NameRendererComponent,
        sortOrder: 'asc',
        visible: true,
        gridTrackSize: '10%'
      },
      this.stepperConfiguration.direction === Direction.INBOUND
        ? {
            header: 'Subscription topic',
            name: 'subscriptionTopic',
            path: 'mapping.subscriptionTopic',
            filterable: true
          }
        : {
            header: 'Publish topic',
            name: 'publishTopic',
            path: 'mapping.publishTopic',
            filterable: true
          },
      this.stepperConfiguration.direction === Direction.INBOUND
        ? {
            header: 'Mapping topic',
            name: 'mappingTopic',
            path: 'mapping.mappingTopic',
            filterable: true
          }
        : {
            header: 'Publish topic sample',
            name: 'publishTopicSample',
            path: 'mapping.publishTopicSample',
            filterable: true
          },
      {
        name: 'targetAPI',
        header: 'API',
        path: 'mapping.targetAPI',
        filterable: true,
        sortable: true,
        dataType: ColumnDataType.TextShort,
        cellRendererComponent: APIRendererComponent,
        gridTrackSize: '7%'
      },
      {
        header: 'For connectors',
        name: 'connectors',
        path: 'connectors',
        filterable: true,
        sortable: false,
        cellRendererComponent: MappingDeploymentRendererComponent
      },
      {
        header: 'Status',
        name: 'tested',
        path: 'mapping',
        filterable: false,
        sortable: false,
        cellRendererComponent: StatusRendererComponent,
        gridTrackSize: '10%'
      },
      this.stepperConfiguration.direction === Direction.INBOUND
        ? {
            // header: 'Test/Debug/Snoop',
            header: 'Templates snooped',
            name: 'snoopedTemplates',
            path: 'mapping',
            filterable: false,
            sortable: false,
            cellCSSClassName: 'text-align-center',
            cellRendererComponent: SnoopedTemplateRendererComponent,
            gridTrackSize: '8%'
          }
        : undefined,
      {
        header: 'Activate',
        name: 'active',
        path: 'mapping.active',
        filterable: false,
        sortable: true,
        cellRendererComponent: StatusActivationRendererComponent,
        gridTrackSize: '9%'
      }
    ];
    return cols;
  }

  onDefineSubscription() {
    this.showConfigSubscription = !this.showConfigSubscription;
  }

  async deleteSubscription(device: IIdentified) {
    // console.log('Delete device', device);
    try {
      await this.mappingService.deleteSubscriptions(device);
      this.alertService.success(
        gettext('Subscription for this device deleted successfully')
      );
      this.loadSubscriptions();
    } catch (error) {
      this.alertService.danger(
        gettext('Failed to delete subscription:') + error
      );
    }
  }

  private async deleteSubscriptionBulkWithConfirmation(ids: string[]) {
    let continueDelete: boolean = false;
    for (let index = 0; index < ids.length; index++) {
      const device2Delete = this.subscription?.devices.find(
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

  async onCommitSubscriptions(deviceList: IIdentified[]) {
    this.subscription = {
      api: API.ALL.name,
      devices: deviceList
    };
    // console.log('Changed deviceList:', this.subscription.devices);
    try {
      this.subscription = await this.mappingService.updateSubscriptions(
        this.subscription
      );
      this.alertService.success(gettext('Subscriptions updated successfully'));
    } catch (error) {
      this.alertService.danger(
        gettext('Failed to update subscriptions:') + error
      );
    }
    this.showConfigSubscription = false;
  }

  async onReload() {
    this.reloadMappingsInBackend();
  }

  private async reloadMappingsInBackend() {
    const response2 = await this.shareService.runOperation(
      Operation.RELOAD_MAPPINGS
    );
    // console.log('Activate mapping response:', response2);
    if (response2.status < 300) {
      this.alertService.success(gettext('Mappings reloaded'));
      this.isConnectionToMQTTEstablished = true;
    } else {
      this.alertService.danger(gettext('Failed to activate mappings'));
    }
  }

  ngOnDestroy() {
    this.destroy$.next(true);
    this.destroy$.unsubscribe();
    this.mappingService.stopChangedMappingEvents();
  }
}