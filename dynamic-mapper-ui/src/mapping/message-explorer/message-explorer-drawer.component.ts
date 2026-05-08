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

import { Component, inject, Input, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { AlertService, BottomDrawerRef, CoreModule } from '@c8y/ngx-components';
import { IIdentified } from '@c8y/client';
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
  direction: 'INBOUND' | 'OUTBOUND';
  deviceId?: string;
  deviceName?: string;
}

@Component({
  selector: 'd11r-message-explorer-drawer',
  templateUrl: './message-explorer-drawer.component.html',
  standalone: true,
  imports: [CoreModule, CommonModule, FormsModule, AssetSelectorModule]
})
export class MessageExplorerDrawerComponent implements OnInit {

  @Input() activeSessionId: string | null = null;

  connectors: ConnectorConfiguration[] = [];
  allConnectors: ConnectorConfiguration[] = [];
  selectedConnectorIdentifier: string = '';
  topic: string = '';
  maxMessages: number = 50;
  direction: 'INBOUND' | 'OUTBOUND' = 'INBOUND';
  /** Selected device for outbound device-scoped monitoring (single selection, optional). */
  selectedDeviceList: IIdentified[] = [];

  private _resolve!: (value: ExplorerStartResult | null) => void;
  result: Promise<ExplorerStartResult | null> = new Promise(resolve => { this._resolve = resolve; });

  private readonly bottomDrawerRef = inject(BottomDrawerRef);
  private readonly connectorConfigService = inject(ConnectorConfigurationService);
  private readonly alertService = inject(AlertService);

  async ngOnInit(): Promise<void> {
    combineLatest([
      this.connectorConfigService.getConfigurations(),
      this.connectorConfigService.getSpecifications()
    ]).subscribe(([configs, specs]) => {
      this.allConnectors = configs.map(c => ({
        ...c,
        supportedDirections: specs.find(s => s.connectorType === c.connectorType)?.supportedDirections ?? []
      }));
      this.filterConnectorsByDirection();
    });
  }

  onDirectionChange(): void {
    this.selectedConnectorIdentifier = '';
    this.selectedDeviceList = [];
    this.filterConnectorsByDirection();
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
  }

  onStart(): void {
    if (this.direction === 'INBOUND' && !this.selectedConnectorIdentifier) {
      this.alertService.warning('Please select a connector.');
      return;
    }
    if (this.direction === 'INBOUND' && (!this.topic || !this.topic.trim())) {
      this.alertService.warning('Please enter a topic to listen on.');
      return;
    }
    const selected = this.connectors.find(c => c.identifier === this.selectedConnectorIdentifier);
    const selectedDevice = this.selectedDeviceList.length > 0 ? this.selectedDeviceList[0] : null;
    const deviceId = selectedDevice ? String((selectedDevice as any).id ?? '') : undefined;
    const deviceName = selectedDevice ? ((selectedDevice as any).name ?? deviceId) : undefined;
    this._resolve({
      connectorIdentifier: this.selectedConnectorIdentifier,
      connectorName: selected?.name ?? this.selectedConnectorIdentifier,
      topic: this.direction === 'OUTBOUND' ? '#' : this.topic.trim(),
      maxMessages: this.maxMessages > 0 ? this.maxMessages : 50,
      direction: this.direction,
      deviceId,
      deviceName
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
