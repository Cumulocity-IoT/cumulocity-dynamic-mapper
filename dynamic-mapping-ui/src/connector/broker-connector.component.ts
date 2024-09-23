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
import { Component, ViewChild } from '@angular/core';
import { AlertService } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { Observable } from 'rxjs';
import packageJson from '../../package.json';
import {
  ConnectorConfiguration,
  ConnectorSpecification,
  ConnectorStatus,
  Feature
} from '../shared';
import { ConnectorConfigurationService } from '../shared/connector-configuration.service';
import { ConnectorConfigurationComponent } from '../shared/connector-configuration/connector-grid.component';

@Component({
  selector: 'd11r-mapping-broker-connector',
  styleUrls: ['./broker-connector.component.style.css'],
  templateUrl: 'broker-connector.component.html'
})
export class BrokerConnectorComponent {
  @ViewChild(ConnectorConfigurationComponent) connectorGrid!: ConnectorConfigurationComponent;
  version: string = packageJson.version;
  monitoring$: Observable<ConnectorStatus>;
  feature: Feature;
  specifications: ConnectorSpecification[] = [];
  configurations: ConnectorConfiguration[];

  constructor(
    public bsModalService: BsModalService,
    public connectorConfigurationService: ConnectorConfigurationService,
    public alertService: AlertService
  ) {}

  refresh() {
    this.connectorGrid.refresh();
  }

  async onConfigurationAdd() {
    this.connectorGrid.onConfigurationAdd();
  }
}
