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
import { combineLatest } from 'rxjs';
import { ConnectorConfiguration, Direction } from '../../shared';
import { ConnectorConfigurationService } from '../../shared/service/connector-configuration.service';
import { StartSessionRequest } from './message-explorer.service';

export interface ExplorerStartResult {
  connectorIdentifier: string;
  connectorName: string;
  topic: string;
  maxMessages: number;
}

@Component({
  selector: 'd11r-message-explorer-drawer',
  templateUrl: './message-explorer-drawer.component.html',
  standalone: true,
  imports: [CoreModule, CommonModule, FormsModule]
})
export class MessageExplorerDrawerComponent implements OnInit {

  @Input() activeSessionId: string | null = null;

  connectors: ConnectorConfiguration[] = [];
  selectedConnectorIdentifier: string = '';
  topic: string = '';
  maxMessages: number = 50;

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
      this.connectors = configs
        .map(c => ({
          ...c,
          supportedDirections: specs.find(s => s.connectorType === c.connectorType)?.supportedDirections ?? []
        }))
        .filter(c => c.supportedDirections.includes(Direction.INBOUND));
    });
  }

  isConnectorDisabled(c: ConnectorConfiguration): boolean {
    return c.enabled === false;
  }

  onStart(): void {
    if (!this.selectedConnectorIdentifier) {
      this.alertService.warning('Please select a connector.');
      return;
    }
    if (!this.topic || !this.topic.trim()) {
      this.alertService.warning('Please enter a topic to listen on.');
      return;
    }
    const selected = this.connectors.find(c => c.identifier === this.selectedConnectorIdentifier);
    this._resolve({
      connectorIdentifier: this.selectedConnectorIdentifier,
      connectorName: selected?.name ?? this.selectedConnectorIdentifier,
      topic: this.topic.trim(),
      maxMessages: this.maxMessages > 0 ? this.maxMessages : 50
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
      maxMessages: this.maxMessages
    };
  }
}
