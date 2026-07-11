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
  CommonModule,
  CoreModule,
  DataGridComponent,
  DataGridModule,
  DataGridService,
  DataSourceModifier,
  DisplayOptions,
  LoadMoreMode,
  Pagination,
  ServerSideDataResult
} from '@c8y/ngx-components';
import {
  API,
  Direction,
  Feature,
  LabelTaggedRendererComponent,
  LoggingEventType,
  NODE2,
  Operation,
  SharedModule,
  ALERT_INFO_TIMEOUT
} from '../../shared';

import { ActivatedRoute, Router } from '@angular/router';
import { IIdentified } from '@c8y/client';
import { Subject } from 'rxjs';
import { SharedService } from '../../shared';
import { MappingService } from '../core/mapping.service';
import { Device, NotificationSubscriptionResponse } from '../shared/mapping.model';
import { SubscriptionService } from '../core/subscription.service';
import { gettext } from '@c8y/ngx-components/gettext';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { DeviceSelectorTreeComponent } from './subscription-static-tree/device-selector-tree.component';
import { GroupSelectorComponent } from './subscription-dynamic-group/group-selector.component';
import { TypeSelectorComponent } from './subscription-dynamic-type/type-selector.component';
import { TypeResyncComponent } from './subscription-dynamic-type/type-resync.component';
import { DeviceSelectorTableComponent } from './subscription-static-table/device-selector-table.component';
import { ConfirmationModalService } from '../../shared/service/confirmation-modal.service';

@Component({
  selector: 'd11r-mapping-subscription-grid',
  templateUrl: 'subscription.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, CommonModule, SharedModule, PopoverModule, DeviceSelectorTreeComponent, DeviceSelectorTableComponent, GroupSelectorComponent, TypeSelectorComponent, TypeResyncComponent],
  providers: [
    DataGridService]

})
export class MappingSubscriptionComponent implements OnInit, OnDestroy {
  @ViewChild('subscriptionGrid') subscriptionGrid!: DataGridComponent;

  constructor() {
    // The grid pulls each page through this callback (server-side load-more).
    this.serverSideDataCallback = this.onDataSourceModifier.bind(this);
  }

  private readonly mappingService = inject(MappingService);
  private readonly subscriptionService = inject(SubscriptionService);
  private readonly sharedService = inject(SharedService);
  private readonly alertService = inject(AlertService);
  private readonly confirmationService = inject(ConfirmationModalService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  showConfigSubscription1 = false;
  showConfigSubscription2 = false;
  showConfigSubscription3 = false;
  showConfigSubscription4 = false;
  showConfigSubscriptionResync = false;

  isConnectionToMQTTEstablished = false;

  subscriptionDevices?: NotificationSubscriptionResponse;
  subscriptionDeviceGroups?: NotificationSubscriptionResponse;
  subscribedDevices: (Device & { groupNames?: string; subscriptionSource?: string })[] = [];
  subscribedDeviceGroups: Device[] = [];
  subscribedDeviceTypes: string[] = [];
  Direction = Direction;

  path: 'static' | 'dynamic' | 'deviceToClientMap' | null = null;
  titleSubscription = 'Subscription devices mapping outbound';

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
      header: 'System ID',
      path: 'id',
      filterable: true,
      sortable: true,
      visible: true,
      dataType: ColumnDataType.Numeric,
    },
    {
      header: 'Name',
      name: 'name',
      path: 'name',
      filterable: true,
      sortable: true,
      dataType: ColumnDataType.TextShort,
    },
    {
      header: 'Type',
      name: 'type',
      path: 'type',
      filterable: true,
      sortable: true,
      dataType: ColumnDataType.TextShort,
    },
    {
      header: 'Groups',
      name: 'groupNames',
      path: 'groupNames',
      filterable: true,
      sortable: true,
      dataType: ColumnDataType.TextShort,
    },
    {
      header: 'Subscription by',
      name: 'subscriptionSource',
      path: 'subscriptionSource',
      filterable: true,
      sortable: true,
      dataType: ColumnDataType.TextShort,
      cellRendererComponent: LabelTaggedRendererComponent
    }
  ];

  private readonly destroy$ = new Subject<void>();

  readonly pagination: Pagination = {
    pageSize: 30,
    currentPage: 1
  };

  actionControlSubscription: ActionControl[] = [];
  bulkActionControlSubscription: BulkActionControl[] = [];
  feature!: Feature;

  // Server-side load-more wiring: the grid requests one page at a time via this callback.
  serverSideDataCallback: (modifier: DataSourceModifier) => Promise<ServerSideDataResult>;
  readonly infiniteScroll: LoadMoreMode = 'auto';
  loadMoreItemsLabel = 'Load more devices';

  // Enrichment context (subscribed groups + types) needed to compute a device's "Subscription by"
  // source. Loaded once; the page callback awaits this before enriching.
  private contextReady?: Promise<void>;



  async ngOnInit(): Promise<void> {
    // Determine subscription path from route
    const href = this.router.url;
    const pathMatch = href.match(/c8y-pkg-dynamic-mapper\/node1\/mappings\/subscription\/(static|dynamic|deviceToClientMap)/);
    this.path = pathMatch ? pathMatch[1] as 'static' | 'dynamic' | 'deviceToClientMap' : null;
    this.titleSubscription = `Subscription (${this.path}) devices mapping outbound`;

    // Load the enrichment context (subscribed groups + types) once. The device list itself is
    // loaded page-by-page by the grid through onDataSourceModifier(), so it is NOT fetched here.
    this.contextReady = this.loadEnrichmentContext();
    await this.contextReady;

    // Setup action controls based on user permissions
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

  /**
   * Loads data one page at a time when the grid requests it (initial load and each "load more").
   * Only the requested page of devices is fetched from the backend; paging metadata drives whether
   * another page can be loaded.
   */
  async onDataSourceModifier(mod: DataSourceModifier): Promise<ServerSideDataResult> {
    // Ensure the group/type context is loaded so "Subscription by" can be computed.
    if (this.contextReady) {
      await this.contextReady;
    }

    const subscription = this.path === 'dynamic'
      ? this.subscriptionService.DYNAMIC_DEVICE_SUBSCRIPTION
      : this.subscriptionService.STATIC_DEVICE_SUBSCRIPTION;
    const currentPage = mod.pagination?.currentPage ?? 1;
    const pageSize = mod.pagination?.pageSize ?? this.pagination.pageSize;

    const response = await this.subscriptionService.getSubscriptionDevice(subscription, currentPage, pageSize);
    this.subscriptionDevices = response ?? undefined;

    const devices = this.enrichDevices(response?.devices ?? []);
    const paging = response?.paging;
    const resolvedPage = paging?.currentPage ?? currentPage;
    // Grand total when the backend supplied it; otherwise fall back so the grid still renders.
    const size = paging?.totalElements ?? ((resolvedPage - 1) * pageSize + devices.length);

    return {
      res: undefined,
      data: devices,
      paging: {
        currentPage: resolvedPage,
        pageSize: paging?.pageSize ?? pageSize,
        nextPage: paging?.hasNext ? resolvedPage + 1 : undefined,
        totalPages: paging?.totalPages,
        totalElements: paging?.totalElements ?? size
      },
      size,
      filteredSize: size
    } as unknown as ServerSideDataResult;
  }

  /**
   * Loads the enrichment context: the set of subscribed groups and types used to label each
   * device's "Subscription by" source (Static / Group / Type / Dynamic).
   */
  private async loadEnrichmentContext(): Promise<void> {
    const [groups, types] = await Promise.all([
      this.subscriptionService.getSubscriptionByDeviceGroup(),
      this.subscriptionService.getSubscriptionByDeviceType()
    ]);
    this.subscriptionDeviceGroups = groups ?? undefined;
    this.subscribedDeviceGroups = groups?.devices ?? [];
    this.subscribedDeviceTypes = types?.types ?? [];
  }

  /**
   * Enriches a page of devices with the display-only `groupNames` string and the `subscriptionSource`
   * label. Pure: operates on the given page, using the already-loaded group/type context.
   */
  private enrichDevices(
    devices: Device[]
  ): (Device & { groupNames?: string; subscriptionSource?: string })[] {
    const subscribedGroupNames = new Set(
      (this.subscribedDeviceGroups ?? []).map(g => g.name).filter(Boolean)
    );
    const subscribedTypesSet = new Set(this.subscribedDeviceTypes ?? []);

    return (devices ?? []).map(d => {
      const groupNames = (d.groups ?? []).join(', ');
      let subscriptionSource: string;
      if (this.path === 'static') {
        subscriptionSource = 'Static';
      } else if ((d.groups ?? []).some(g => subscribedGroupNames.has(g))) {
        subscriptionSource = 'Group';
      } else if (d.type && subscribedTypesSet.has(d.type)) {
        subscriptionSource = 'Type';
      } else {
        subscriptionSource = 'Dynamic';
      }
      return { ...d, groupNames, subscriptionSource };
    });
  }

  /**
   * Loads the FULL subscribed-device list for the manage-subscription drawers. The drawers re-commit
   * the complete desired set, so they must not operate on a single grid page.
   */
  private async loadAllSubscribedDevicesForDrawer(): Promise<void> {
    const subscription = this.path === 'dynamic'
      ? this.subscriptionService.DYNAMIC_DEVICE_SUBSCRIPTION
      : this.subscriptionService.STATIC_DEVICE_SUBSCRIPTION;
    const response = await this.subscriptionService.getSubscriptionDevice(subscription);
    this.subscribedDevices = this.enrichDevices(response?.devices ?? []);
  }

  async loadSubscriptionByDeviceGroup(): Promise<void> {
    this.subscriptionDeviceGroups = await this.subscriptionService.getSubscriptionByDeviceGroup();
    this.subscribedDeviceGroups = this.subscriptionDeviceGroups?.devices ?? [];
  }

  async loadSubscriptionByDeviceType(): Promise<void> {
    const filter = await this.subscriptionService.getSubscriptionByDeviceType();
    this.subscribedDeviceTypes = filter?.types ?? [];
  }

  async onDefineSubscription1(): Promise<void> {
    // Opening the drawer: load the full subscribed-device list first so the selection reflects
    // every currently-subscribed device, not just the current grid page.
    if (!this.showConfigSubscription1) {
      await this.loadAllSubscribedDevicesForDrawer();
    }
    this.showConfigSubscription1 = !this.showConfigSubscription1;
  }

  async onDefineSubscription2(): Promise<void> {
    if (!this.showConfigSubscription2) {
      await this.loadAllSubscribedDevicesForDrawer();
    }
    this.showConfigSubscription2 = !this.showConfigSubscription2;
  }

  onDefineSubscription3(): void {
    this.showConfigSubscription3 = !this.showConfigSubscription3;
  }

  onDefineSubscription4(): void {
    this.showConfigSubscription4 = !this.showConfigSubscription4;
  }

  onDefineSubscriptionResync(): void {
    this.showConfigSubscriptionResync = !this.showConfigSubscriptionResync;
  }

  onRefresh(): void {
    this.subscriptionGrid?.reload();
  }

  async deleteSubscription(device: IIdentified): Promise<void> {
    // console.log('Delete device', device);
    try {
      const subscription = this.path === "dynamic" ? this.subscriptionService.DYNAMIC_DEVICE_SUBSCRIPTION : this.subscriptionService.STATIC_DEVICE_SUBSCRIPTION;

      await this.subscriptionService.deleteSubscriptionDevice(device, subscription);
      this.alertService.success(
        gettext('Subscription for this device deleted successfully')
      );
      this.subscriptionGrid?.reload();
    } catch (error) {
      this.alertService.danger(
        gettext('Failed to delete subscription:') + error
      );
    }
  }

  private async deleteSubscriptionBulkWithConfirmation(ids: string[]): Promise<void> {
    let continueDelete: boolean = false;
    for (let index = 0; index < ids.length; index++) {
      // Deletion only needs the id; the selected ids come from the grid, so we don't rely on the
      // full device list being loaded in memory.
      const device2Delete: IIdentified = { id: ids[index] };
      if (index === 0) {
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
    this.mappingService.refreshMappings(Direction.OUTBOUND);
    this.subscriptionGrid.setAllItemsSelected(false);
  }

  private async deleteSubscriptionWithConfirmation(
    device2Delete: IIdentified,
    confirmation: boolean = true,
    multiple: boolean = false
  ): Promise<boolean> {
    let result = false;

    if (confirmation) {
      result = await this.confirmationService.confirmDeletion('subscription', multiple);
      if (result) {
        await this.deleteSubscription(device2Delete);
      }
    } else {
      await this.deleteSubscription(device2Delete);
      result = true;
    }

    this.subscriptionGrid.setAllItemsSelected(false);
    return result;
  }

  async onCommitSubscriptionDevice(deviceList: IIdentified[]): Promise<void> {
    const subscriptionDevices = {
      api: API.ALL.name,
      devices: deviceList as Device[]
    };
    try {
      await this.subscriptionService.updateSubscriptionDevice(
        subscriptionDevices
      );
      this.subscriptionGrid?.reload();
      this.alertService.add({ text: gettext('Subscription request submitted. Subscriptions are processed asynchronously – verify the result in the list below and check Service Events for details.'), type: 'info', timeout: ALERT_INFO_TIMEOUT });
    } catch (error) {
      this.alertService.danger(
        gettext('Failed to update subscriptions:') + error
      );
    }
    this.showConfigSubscription1 = false;
    this.showConfigSubscription2 = false;
  }

  async onCommitSubscriptionByDeviceGroup(deviceList: IIdentified[]): Promise<void> {
    const subscriptionDevices = {
      api: API.ALL.name,
      devices: deviceList as Device[]
    };
    try {
      await this.subscriptionService.updateSubscriptionByDeviceGroup(
        subscriptionDevices
      );
      await this.loadSubscriptionByDeviceGroup();
      this.subscriptionGrid?.reload();
      this.alertService.add({ text: gettext('Subscription request submitted. Subscriptions are processed asynchronously – verify the result in the list below and check Service Events for details.'), type: 'info', timeout: ALERT_INFO_TIMEOUT });
    } catch (error) {
      this.alertService.danger(
        gettext('Failed to update subscriptions:') + error
      );
    }
    this.showConfigSubscription3 = false;
  }

  async onCommitSubscriptionByDeviceType(typeList: string[]): Promise<void> {
    const subscriptionDevices = {
      api: API.ALL.name,
      types: typeList as string[]
    };
    try {
      await this.subscriptionService.updateSubscriptionByDeviceType(
        subscriptionDevices
      );
      await this.loadSubscriptionByDeviceType();
      this.subscriptionGrid?.reload();
      this.alertService.add({ text: gettext('Subscription request submitted. Subscriptions are processed asynchronously – verify the result in the list below and check Service Events for details.'), type: 'info', timeout: ALERT_INFO_TIMEOUT });
    } catch (error) {
      this.alertService.danger(
        gettext('Failed to update subscriptions:') + error
      );
    }
    this.showConfigSubscription4 = false;
  }

  async resyncType(type: string): Promise<void> {
    const confirmed = await this.confirmationService.confirmWarning(
      gettext('Resync existing devices'),
      gettext('This rescans the full inventory for type') + ` "${type}" ` +
      gettext('and subscribes any existing device not already covered. This can take a while on large inventories. Continue?')
    );
    if (!confirmed) {
      return;
    }

    try {
      await this.subscriptionService.resyncTypeSubscription(type);
      this.alertService.add({ text: gettext('Resync request submitted. Existing devices of this type are being subscribed in the background – verify the result in the list below and check Service Events for details.'), type: 'info', timeout: ALERT_INFO_TIMEOUT });
    } catch (error) {
      this.alertService.danger(
        gettext('Failed to resync type subscription:') + error
      );
    }
  }

  navigateToServiceEvents(): void {
    this.router.navigate([`/c8y-pkg-dynamic-mapper/${NODE2}/monitoring/serviceEvent`], {
      queryParams: { type: LoggingEventType.SUBSCRIPTION_DEDUPLICATION_EVENT_TYPE }
    });
  }

  async onReload(): Promise<void> {
    this.reloadMappingsInBackend();
  }

  private async reloadMappingsInBackend(): Promise<void> {
    const response2 = await this.sharedService.runOperation(
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
    this.destroy$.next();
    this.destroy$.complete();
  }
}