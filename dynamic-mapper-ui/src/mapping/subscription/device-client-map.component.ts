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
  BulkActionControl,
  Column,
  ColumnDataType,
  DataGridComponent,
  DisplayOptions,
  Pagination,
} from '@c8y/ngx-components';
import {
  Direction,
  Feature,
  MappingType,
} from '../../shared';

import { ActivatedRoute, Router } from '@angular/router';
import { Subject } from 'rxjs';
import { DeploymentMapEntry } from '../../shared';
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
    this.loadAllClientRelations();
  }

  private subscriptionService = inject(SubscriptionService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);


  clientRelations: any;
  mapEntries: any[];
  Direction = Direction;

  titleMapping: string;

  readonly titleSubscription: string = 'Device to Client Map';

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

  async loadAllClientRelations(): Promise<void> {
    this.clientRelations = await this.subscriptionService.getAllClientRelations();

    // Initialize mapEntries array
    this.mapEntries = [];

    if (this.clientRelations && this.clientRelations.relations) {
      this.mapEntries = this.clientRelations.relations;
    }

    this.deviceToClientGrid?.reload();
  }


  ngOnDestroy(): void {
    this.destroy$.next(true);
    this.destroy$.unsubscribe();
  }
}