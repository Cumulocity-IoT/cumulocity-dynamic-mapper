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
import { API, Direction, LabelTaggedRendererComponent, Mapping } from '../../shared';
import { MappingDeploymentRendererComponent } from '../renderer/mapping-deployment.renderer.component';
import { MappingIdCellRendererComponent } from '../renderer/mapping-id-cell.renderer.component';
import { MappingStatusActivationRendererComponent } from '../renderer/mapping-status-activation.renderer.component';
import { StatusRendererComponent } from '../renderer/status.renderer.component';

/**
 * The grid's columns for one direction: inbound mappings show the mapping topic, outbound ones the
 * publish topic. `openVersions` is called with the row when the version/status cell is clicked.
 */
export function buildMappingColumns(direction: Direction, openVersions: (row: any) => void): Column[] {
  const cols: (Column | undefined)[] = [
    {
      name: 'name',
      header: gettext('Name'),
      path: 'mapping.name',
      filterable: true,
      sortable: true,
      dataType: ColumnDataType.TextShort,
      cellRendererComponent: MappingIdCellRendererComponent,
      // No sortOrder here: the grid sorts by *every* column that carries one, in column
      // order, and clicking a header only changes that one column. A pre-set 'asc' on the
      // first column therefore stays the primary key forever and makes sorting by API or
      // Activate look broken. The default alphabetical order is produced by sorting the
      // rows below instead.
      visible: true,
      gridTrackSize: '10%'
    },
    {
      name: 'identifier',
      header: gettext('Identifier'),
      path: 'mapping.identifier',
      filterable: false,
      dataType: ColumnDataType.TextShort,
      visible: false,
      gridTrackSize: '0%'
    },
    direction === Direction.INBOUND
      ? undefined
      : {
        header: gettext('Publish topic'),
        name: 'publishTopic',
        path: 'mapping.publishTopic',
        dataType: ColumnDataType.TextShort,
        filterable: true,
        sortable: true
      },
    direction === Direction.INBOUND
      ? {
        header: gettext('Mapping topic'),
        name: 'mappingTopic',
        path: 'mapping.mappingTopic',
        dataType: ColumnDataType.TextShort,
        filterable: true,
        sortable: true
      }
      : undefined,
    {
      name: 'targetAPI',
      header: 'API',
      path: 'mapping.targetAPI',
      filterable: true,
      sortable: true,
      dataType: ColumnDataType.TextShort,
      cellRendererComponent: LabelTaggedRendererComponent,
      gridTrackSize: '9%'
    },
    {
      header: gettext('For connectors'),
      name: 'connectors',
      path: 'connectors',
      filterable: true,
      sortable: false,
      dataType: ColumnDataType.TextShort,
      cellRendererComponent: MappingDeploymentRendererComponent
    },
    {
      header: gettext('Version/Status'),
      name: 'status',
      path: 'mapping',
      filterable: false,
      sortable: false,
      dataType: ColumnDataType.TextShort,
      cellRendererComponent: StatusRendererComponent,
      // Makes the cell a link that opens the version drawer for this row. Read by
      // StatusRendererComponent as context.property['callback'] — 'callback' is not part of
      // the Column interface, hence the cast, matching how the connector grid does it.
      callback: openVersions,
      gridTrackSize: '16%'
    } as Column,
    {
      header: gettext('Activate'),
      name: 'active',
      path: 'mapping.active',
      filterable: false,
      sortable: true,
      cellRendererComponent: MappingStatusActivationRendererComponent,
      gridTrackSize: '9%'
    }
  ];
  return cols.filter((c): c is Column => !!c);
}
