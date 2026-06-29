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

import { Component, inject, Input, OnInit, OnChanges } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { AlertService, BottomDrawerRef, CoreModule } from '@c8y/ngx-components';
import { IIdentified, InventoryService } from '@c8y/client';
import { combineLatest } from 'rxjs';
import { ConnectorConfiguration, Direction } from '../../shared';
import { ConnectorConfigurationService } from '../../shared/service/connector-configuration.service';
import { StartSessionRequest } from './message-explorer.service';
import { AssetSelectionChangeEvent, AssetSelectorModule } from '@c8y/ngx-components/assets-navigator';

export interface ExplorerStartResult {
  connectorIdentifier: string;
  connectorName: string;
  topic: string;
  maxMessages: number;
  sessionTTLMinutes: number;
  direction: 'INBOUND' | 'OUTBOUND';
  sourceId?: string;
  deviceName?: string;
  deviceType?: string | null;
  deviceTypeFilter?: string; // device type filter (OUTBOUND only)
}

export interface ExplorerSessionSnapshot {
  connectorIdentifier: string;
  topic: string;
  maxMessages: number;
  sessionTTLMinutes: number;
  direction: 'INBOUND' | 'OUTBOUND';
  sourceId?: string;
  deviceTypeFilter?: string;
}

@Component({
  selector: 'd11r-message-explorer-drawer',
  host: { class: 'flex-grow d-col fit-h' },
  templateUrl: './message-explorer-drawer.component.html',
  standalone: true,
  imports: [CoreModule, CommonModule, FormsModule, AssetSelectorModule]
})
export class MessageExplorerDrawerComponent implements OnInit {

  @Input() activeSessionId: string | null = null;
  /** When set, the drawer opens in edit mode pre-filled with the current session values. */
  @Input() editSnapshot: ExplorerSessionSnapshot | null = null;

  get editMode(): boolean { return this.editSnapshot !== null; }

  connectors: ConnectorConfiguration[] = [];
  allConnectors: ConnectorConfiguration[] = [];
  selectedConnectorIdentifier: string = '';
  topic: string = '';
  maxMessages: number = 50;
  sessionTTLMinutes: number = 10;
  direction: 'INBOUND' | 'OUTBOUND' = 'INBOUND';
  /** Selected source (device or group) for outbound monitoring (single selection). */
  selectedDeviceList: IIdentified[] = [];
  /** Optional device type filter for outbound monitoring — shows all devices of this type. */
  deviceTypeFilter: string = '';
  /** Controls which OUTBOUND filter mode is active: asset selector or device type input. */
  outboundFilterMode: 'source' | 'deviceType' = 'source';

  private _resolve!: (value: ExplorerStartResult | null) => void;
  result: Promise<ExplorerStartResult | null> = new Promise(resolve => { this._resolve = resolve; });

  private readonly bottomDrawerRef = inject(BottomDrawerRef);
  private readonly connectorConfigService = inject(ConnectorConfigurationService);
  private readonly alertService = inject(AlertService);
  private readonly inventoryService = inject(InventoryService);

  selectedDeviceType: string | null = null;
  private deviceTypeFetch: Promise<string | null> = Promise.resolve(null);

  async ngOnInit(): Promise<void> {
    if (this.editSnapshot) {
      this.direction = this.editSnapshot.direction;
      this.topic = this.editSnapshot.topic;
      this.maxMessages = this.editSnapshot.maxMessages;
      this.sessionTTLMinutes = this.editSnapshot.sessionTTLMinutes;
      this.deviceTypeFilter = this.editSnapshot.deviceTypeFilter ?? '';
      if (this.editSnapshot.sourceId) {
        this.outboundFilterMode = 'source';
      } else if (this.editSnapshot.deviceTypeFilter) {
        this.outboundFilterMode = 'deviceType';
      }
    }
    combineLatest([
      this.connectorConfigService.getConfigurations(),
      this.connectorConfigService.getSpecifications()
    ]).subscribe(([configs, specs]) => {
      this.allConnectors = configs.map(c => ({
        ...c,
        supportedDirections: specs.find(s => s.connectorType === c.connectorType)?.supportedDirections ?? []
      }));
      this.filterConnectorsByDirection();
      // Restore selected connector AFTER options are rendered — setting it before the
      // <option> elements exist causes Angular ngModel to silently clear the value.
      if (this.editSnapshot) {
        this.selectedConnectorIdentifier = this.editSnapshot.connectorIdentifier;
      }
    });
  }

  onDirectionChange(): void {
    this.selectedConnectorIdentifier = '';
    this.selectedDeviceList = [];
    this.deviceTypeFilter = '';
    this.outboundFilterMode = 'source';
    this.filterConnectorsByDirection();
  }

  onOutboundFilterModeChange(): void {
    if (this.outboundFilterMode === 'source') {
      this.deviceTypeFilter = '';
    } else {
      this.selectedDeviceList = [];
      this.selectedDeviceType = null;
    }
  }

  private filterConnectorsByDirection(): void {
    const dir = this.direction === 'OUTBOUND' ? Direction.OUTBOUND : Direction.INBOUND;
    this.connectors = this.allConnectors.filter(c =>
      (c as any).supportedDirections?.includes(dir)
    );
  }

  isConnectorDisabled(c: ConnectorConfiguration): boolean {
    return c.enabled === false;
  }

  onDeviceSelected(event: AssetSelectionChangeEvent): void {
    const items = event.items;
    this.selectedDeviceList = Array.isArray(items) ? items : (items ? [items] : []);
    const selected = this.selectedDeviceList[0] as any;
    if (selected?.id) {
      // Asset selector may already return a full managed object with type
      if (selected.type) {
        this.selectedDeviceType = selected.type;
        this.deviceTypeFetch = Promise.resolve(selected.type);
      } else {
        this.deviceTypeFetch = this.inventoryService.detail(String(selected.id))
          .then(({ data }) => {
            this.selectedDeviceType = (data as any)['type'] ?? null;
            return this.selectedDeviceType;
          })
          .catch((err) => { this.selectedDeviceType = null; return null; });
      }
    } else {
      this.selectedDeviceType = null;
      this.deviceTypeFetch = Promise.resolve(null);
    }
  }

  async onStart(): Promise<void> {
    if (this.direction === 'INBOUND' && !this.selectedConnectorIdentifier) {
      this.alertService.warning('Please select a connector.');
      return;
    }
    if (this.direction === 'INBOUND' && (!this.topic || !this.topic.trim())) {
      this.alertService.warning('Please enter a topic to listen on.');
      return;
    }
    const selected = this.connectors.find(c => c.identifier === this.selectedConnectorIdentifier);
    const useSourceMode = this.direction === 'OUTBOUND' && this.outboundFilterMode === 'source';
    const useTypeMode = this.direction === 'OUTBOUND' && this.outboundFilterMode === 'deviceType';
    const selectedDevice = useSourceMode && this.selectedDeviceList.length > 0 ? this.selectedDeviceList[0] : null;
    const sourceId = selectedDevice ? String((selectedDevice as any).id ?? '') : undefined;
    const deviceName = selectedDevice ? ((selectedDevice as any).name ?? sourceId) : undefined;
    // Await in case the user clicked Start before the detail() promise resolved
    const deviceType = sourceId ? await this.deviceTypeFetch : null;
    this._resolve({
      connectorIdentifier: this.selectedConnectorIdentifier,
      connectorName: selected?.name ?? this.selectedConnectorIdentifier,
      topic: this.direction === 'OUTBOUND' ? '#' : this.topic.trim(),
      maxMessages: this.maxMessages > 0 ? this.maxMessages : 50,
      sessionTTLMinutes: this.sessionTTLMinutes > 0 ? this.sessionTTLMinutes : 10,
      direction: this.direction,
      sourceId,
      deviceName,
      deviceType,
      deviceTypeFilter: useTypeMode ? (this.deviceTypeFilter.trim() || undefined) : undefined
    });
    this.bottomDrawerRef.close();
  }

  onCancel(): void {
    this._resolve(null);
    this.bottomDrawerRef.close();
  }

  buildRequest(): StartSessionRequest {
    return {
      connectorIdentifier: this.selectedConnectorIdentifier,
      topic: this.topic.trim(),
      maxMessages: this.maxMessages,
      direction: this.direction
    };
  }
}
