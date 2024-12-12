/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
import { Component, ViewEncapsulation } from '@angular/core';
import {
  AlertService,
  CellRendererContext,
  gettext
} from '@c8y/ngx-components';
import { Direction, Operation, SharedService } from '../..';
import { ConnectorConfigurationService } from '../../service/connector-configuration.service';
import { HttpStatusCode } from '@angular/common/http';

/**
 * The example component for custom cell renderer.
 * It gets `context` with the current row item and the column.
 * Additionally, a service is injected to provide a helper method.
 * The template displays the icon and the label with additional styling.
 */
@Component({
  encapsulation: ViewEncapsulation.None,
  template: `
    <div>
      <label
        title="{{ 'Toggle connection activation' | translate }}"
        class="c8y-switch"
      >
        <input
          type="checkbox"
          [checked]="context.value"
          (change)="onConfigurationToggle()"
        />
        <span></span>
      </label>
    </div>
  `
})
export class StatusEnabledRendererComponent {
  constructor(
    public context: CellRendererContext,
    public alertService: AlertService,
    public sharedService: SharedService,
    private connectorConfigurationService: ConnectorConfigurationService
  ) {
    // console.log('Status', context, context.value);
  }

  async onConfigurationToggle() {
    const configuration = this.context.item;
    const response1 = await this.sharedService.runOperation(
      configuration.enabled ? { operation: Operation.DISCONNECT, parameter: { connectorIdent: configuration.ident } } : {
        operation: Operation.CONNECT,
        parameter: { connectorIdent: configuration.ident }
      }
    );
    // console.log('Details toggle activation to broker', response1);
    if (response1.status === HttpStatusCode.Created) {
      // if (response1.status === HttpStatusCode.Created && response2.status === HttpStatusCode.Created) {
      this.alertService.success(gettext('Connection updated successfully.'));
    } else {
      this.alertService.danger(gettext('Failed to establish connection!'));
    }
    this.reloadData();
    this.sharedService.refreshMappings(Direction.INBOUND);
    this.sharedService.refreshMappings(Direction.OUTBOUND);
  }
  reloadData(): void {
    this.connectorConfigurationService.updateConnectorConfigurations();
  }
}
