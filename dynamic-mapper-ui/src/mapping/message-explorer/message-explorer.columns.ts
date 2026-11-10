/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
import { Column, ColumnDataType } from '@c8y/ngx-components';
import { gettext } from '@c8y/ngx-components/gettext';
import {
  MessageExplorerDateRendererComponent,
  MessageExplorerPayloadRendererComponent,
  MessageExplorerTruncateRendererComponent
} from './message-explorer-payload.renderer.component';

/**
 * Columns only present for some broker protocols: clientId is set by the MQTT connectors, key by Kafka (inbound
 * only: the outbound record key is applied further downstream, after the explorer has already captured the
 * message). They are shown only once a captured message actually carries a value.
 */
export const OPTIONAL_EXPLORER_COLUMNS: readonly string[] = ['clientId', 'key'];

export const EXPLORER_COLUMNS: Column[] = [
  {
    name: 'seqNo',
    header: '#',
    path: 'seqNo',
    dataType: ColumnDataType.TextShort,
    sortable: false,
    filterable: false,
    gridTrackSize: '5%'
  },
  {
    name: 'receivedAt',
    header: gettext('Received at'),
    path: 'receivedAt',
    dataType: ColumnDataType.TextShort,
    sortable: true,
    filterable: false,
    cellRendererComponent: MessageExplorerDateRendererComponent,
    gridTrackSize: '12%'
  },
  {
    name: 'clientId',
    header: gettext('Client ID'),
    path: 'clientId',
    dataType: ColumnDataType.TextShort,
    sortable: false,
    filterable: false,
    cellRendererComponent: MessageExplorerTruncateRendererComponent,
    gridTrackSize: '12%'
  },
  {
    name: 'key',
    header: gettext('Key'),
    path: 'key',
    dataType: ColumnDataType.TextShort,
    sortable: false,
    filterable: false,
    cellRendererComponent: MessageExplorerTruncateRendererComponent,
    gridTrackSize: '14%'
  },
  {
    name: 'topic',
    header: gettext('Topic'),
    path: 'topic',
    dataType: ColumnDataType.TextShort,
    sortable: false,
    filterable: true,
    gridTrackSize: '12%'
  },
  {
    name: 'payload',
    header: gettext('Payload'),
    path: 'payload',
    dataType: ColumnDataType.TextShort,
    sortable: false,
    filterable: false,
    cellRendererComponent: MessageExplorerPayloadRendererComponent
  }
];
