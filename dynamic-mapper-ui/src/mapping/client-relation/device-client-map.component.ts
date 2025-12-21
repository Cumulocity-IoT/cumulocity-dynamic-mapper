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
  CoreModule,
  DataGridComponent,
  DisplayOptions,
  Pagination,
} from '@c8y/ngx-components';
import {
  ConfirmationModalComponent,
  Direction,
  Feature,
  MappingType,
} from '../../shared';

import { ActivatedRoute, Router } from '@angular/router';
import { Subject } from 'rxjs';
import { IIdentified } from '@c8y/client';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { ClientRelationService } from '../core/client-relation.service';
import { gettext } from '@c8y/ngx-components/gettext';
import { CommonModule } from '@angular/common';
import { ClientRelationStepperComponent } from './client-relation-stepper.component';
import { PopoverModule } from 'ngx-bootstrap/popover';

@Component({
  selector: 'd11r-mapping-device-client-map',
  templateUrl: 'device-client-map.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, CommonModule, PopoverModule, ClientRelationStepperComponent]

})
export class DeviceClientMapComponent implements OnInit, OnDestroy {


  @ViewChild('deviceToClientGrid') deviceToClientGrid: DataGridComponent;

  constructor(
  ) {
    // console.log('constructor');
    const href = this.router.url;
    this.loadAllClientRelations();
  }

  private clientRelationService = inject(ClientRelationService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private bsModalService = inject(BsModalService);
  private alertService = inject(AlertService);

  clientRelations: any;
  mapEntries: any[];
  Direction = Direction;

  titleMapping: string;

  readonly titleRelation: string = 'Device to Client Map';

  readonly displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: false,
    gridHeader: true,
    hover: true
  };

  columnsRelations: Column[] = [
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

  actionControlRelation: ActionControl[] = [];
  bulkActionControlRelation: BulkActionControl[] = [];
  feature: Feature;
  showAddRelation: Boolean = false;

  async ngOnInit(): Promise<void> {
    this.feature = this.route.snapshot.data['feature'];
    if (this.feature?.userHasMappingAdminRole || this.feature?.userHasMappingCreateRole) {
      this.bulkActionControlRelation.push({
        type: BuiltInActionType.Delete,
        callback: this.deleteRelationBulkWithConfirmation.bind(this)
      });
      this.actionControlRelation.push({
        type: BuiltInActionType.Delete,
        callback: this.deleteRelationWithConfirmation.bind(this)
      });
    }
  }

  async loadAllClientRelations(): Promise<void> {
    this.clientRelations = await this.clientRelationService.getAllClientRelations();

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

  async onDeleteAllClientRelations() {
    let result: boolean = false;
    const initialState = {
      title: 'Delete all relations ',
      message: 'You are about to delete all relations. Do you want to proceed to delete ALL?'
      ,
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
      await this.clientRelationService.deleteAllClientRelations();
      this.alertService.success(
        gettext('Relations deleted successfully')
      );
      this.loadAllClientRelations();
    }
  }

  async deleteRelation(device: IIdentified): Promise<void> {
    // console.log('Delete device', device);
    try {
      await this.clientRelationService.deleteClientRelationForDevice(device);
      this.alertService.success(
        gettext('Relation for this device deleted successfully')
      );
      this.loadAllClientRelations();
    } catch (error) {
      this.alertService.danger(
        gettext('Failed to delete relation:') + error
      );
    }
  }

  onAddRelations() {
    this.showAddRelation = true;
  }

  async onCommitRelations(update: { client: string, devices: string[] }) {
    try {
      await this.clientRelationService.addOrUpdateClientRelations(update.client, update.devices); this.alertService.success(
        gettext('Relations updated successfully')
      );
      this.loadAllClientRelations();
    } catch (error) {
      this.alertService.danger(
        gettext('Failed to update relations:') + error
      );
    }
    this.showAddRelation = false;
  }

  private async deleteRelationBulkWithConfirmation(ids: string[]): Promise<void> {
    let continueDelete: boolean = false;
    for (let index = 0; index < ids.length; index++) {
      const device2Delete = this.clientRelations?.relations.find(
        (de) => de.id == ids[index]
      );
      if (index == 0) {
        continueDelete = await this.deleteRelationWithConfirmation(
          device2Delete,
          true,
          true
        );
      } else if (continueDelete) {
        this.deleteRelation(device2Delete);
      }
    }
    this.deviceToClientGrid.setAllItemsSelected(false);
  }

  private async deleteRelationWithConfirmation(
    device2Delete: IIdentified,
    confirmation: boolean = true,
    multiple: boolean = false
  ): Promise<boolean | PromiseLike<boolean>> {
    let result: boolean = false;
    if (confirmation) {
      const initialState = {
        title: multiple ? 'Delete relations' : 'Delete relation',
        message: multiple
          ? 'You are about to delete relations. Do you want to proceed to delete ALL?'
          : 'You are about to delete a relation. Do you want to proceed?',
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
        await this.deleteRelation(device2Delete);
      }
    }
    this.deviceToClientGrid.setAllItemsSelected(false);
    return result;
  }
}