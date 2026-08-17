/*
 * Copyright (c) 2026 Cumulocity GmbH
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
import { CommonModule } from '@angular/common';
import { Component, Input, ViewEncapsulation } from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';
import {
  connectorStatusToSeverity,
  ConnectorStatus,
  ConnectorStatusHistory,
  getSeverityBadgeClass
} from './connector-log.model';

/**
 * Renders one connector connection-lifecycle session (see ConnectorStatusHistory) as a compact
 * timeline of its transitions — the visual equivalent of a Cumulocity Operation's
 * "History of changes" tab. Lives in `shared` since it's mounted inside collapsible list items
 * on both the Monitoring > Service events page and the per-connector Details & logs page.
 */
@Component({
  selector: 'd11r-connector-status-history',
  templateUrl: 'connector-status-history.component.html',
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CommonModule, CoreModule]
})
export class ConnectorStatusHistoryComponent {
  @Input() history: ConnectorStatusHistory;

  getSeverityClass(status: ConnectorStatus): string {
    return getSeverityBadgeClass(connectorStatusToSeverity(status));
  }
}
