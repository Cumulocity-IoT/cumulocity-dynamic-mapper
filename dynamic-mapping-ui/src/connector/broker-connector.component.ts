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
import { Component, OnInit } from '@angular/core';
import { AlertService, gettext } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { from, Observable } from 'rxjs';
import packageJson from '../../package.json';
import {
  ConnectorConfiguration,
  ConnectorSpecification,
  ConnectorStatus,
  Feature,
  SharedService,
  uuidCustom
} from '../shared';
import { ConnectorConfigurationService } from '../shared/connector-configuration.service';
import { ConfigurationConfigurationModalComponent } from '../shared';

@Component({
  selector: 'd11r-mapping-broker-connector',
  styleUrls: ['./broker-connector.component.style.css'],
  templateUrl: 'broker-connector.component.html'
})
export class BrokerConnectorComponent implements OnInit {
  version: string = packageJson.version;
  monitoring$: Observable<ConnectorStatus>;
  feature: Feature;
  specifications: ConnectorSpecification[] = [];
  configurations: ConnectorConfiguration[];

  constructor(
    public bsModalService: BsModalService,
    public connectorConfigurationService: ConnectorConfigurationService,
    public alertService: AlertService,
    private sharedService: SharedService
  ) {}

  ngOnInit() {
    // console.log('Running version', this.version);
    from(
      this.connectorConfigurationService.getConnectorSpecifications()
    ).subscribe((specs) => {
      this.specifications = specs;
    });
    this.connectorConfigurationService
      .getConnectorConfigurationsLive()
      .subscribe((confs) => {
        this.configurations = confs;
      });
    this.loadData();
  }

  refresh() {
    this.connectorConfigurationService.resetCache();
    this.loadData();
  }

  loadData(): void {
    this.connectorConfigurationService.startConnectorConfigurations();
  }

  async onConfigurationAdd() {
    const configuration: Partial<ConnectorConfiguration> = {
      properties: {},
      ident: uuidCustom()
    };
    const initialState = {
      add: true,
      configuration: configuration,
      specifications: this.specifications,
      configurationsCount: this.configurations.length
    };
    const modalRef = this.bsModalService.show(
      ConfigurationConfigurationModalComponent,
      {
        initialState
      }
    );
    modalRef.content.closeSubject.subscribe(async (addedConfiguration) => {
      // console.log('Configuration after edit:', addedConfiguration);
      if (addedConfiguration) {
        // avoid to include status$
        const clonedConfiguration = {
          ident: addedConfiguration.ident,
          connectorType: addedConfiguration.connectorType,
          enabled: addedConfiguration.enabled,
          name: addedConfiguration.name,
          properties: addedConfiguration.properties
        };
        const response =
          await this.connectorConfigurationService.createConnectorConfiguration(
            clonedConfiguration
          );
        if (response.status < 300) {
          this.alertService.success(
            gettext('Added successfully configuration')
          );
        } else {
          this.alertService.danger(
            gettext('Failed to update connector configuration')
          );
        }
        this.loadData();
      }
    });
  }
}
