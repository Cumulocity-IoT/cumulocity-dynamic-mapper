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
  inject,
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
import { DeploymentMapEntry, SharedService } from '../../shared';
import { MappingService } from '../core/mapping.service';
import { Device, NotificationSubscriptionResponse } from '../shared/mapping.model';
import { SubscriptionService } from '../core/subscription.service';

@Component({
  selector: 'd11r-mapping-device-client-map',
  templateUrl: 'device-client-map.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class DeviceClientMapComponent implements OnInit, OnDestroy {
  @ViewChild('deviceToClientGrid') deviceToClientGrid: DataGridComponent;

  constructor(
  ) {
    // console.log('constructor');
    const href = this.router.url;
    this.loadAllClientMappings();
  }

  private mappingService = inject(MappingService);
  private subscriptionService = inject(SubscriptionService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);


  clientMappings: any;
  mapEntries: any[];
  Direction = Direction;

  titleMapping: string;

  readonly titleSubscription: string = 'Device to Client Map';
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
      name: 'Id',
      header: 'Device ID',
      path: 'id',
      filterable: false,
      dataType: ColumnDataType.TextShort,
      visible: true
    },
    {
      header: 'Client',
      name: 'client',
      path: 'client',
      filterable: true
    },
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
  }

async loadAllClientMappings(): Promise<void> {
    this.clientMappings = await this.subscriptionService.getAllClientMappings();
    
    // Initialize mapEntries array
    this.mapEntries = [];
    
    // Parse mappings object
    if (this.clientMappings && this.clientMappings.mappings) {
        for (const [id, client] of Object.entries(this.clientMappings.mappings)) {
            this.mapEntries.push({
                id: id,
                client: client
            });
        }
    }
    
    this.deviceToClientGrid?.reload();
}


  ngOnDestroy(): void {
    this.destroy$.next(true);
    this.destroy$.unsubscribe();
    this.mappingService.stopChangedMappingEvents();
  }
}