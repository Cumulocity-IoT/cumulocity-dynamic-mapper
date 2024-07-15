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
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { Observable } from 'rxjs';
import packageJson from '../../../package.json';

import * as _ from 'lodash';
import { ConfirmationModalComponent } from '../confirmation/confirmation-modal.component';
import { ConnectorConfigurationService } from '../connector-configuration.service';
import {
  ConnectorStatus,
  StatusEventTypes
} from '../connector-status/connector-status.model';
import { Direction, Feature } from '../model/shared.model';
import { uuidCustom } from '../model/util';
import { Operation } from '../shared.module';
import { SharedService } from '../shared.service';
import { ConfigurationConfigurationModalComponent } from './connector-configuration-modal.component';
import {
  ConnectorConfiguration,
  ConnectorSpecification
} from './connector.model';

@Component({
  selector: 'd11r-mapping-connector-configuration',
  styleUrls: ['./connector-configuration.component.style.css'],
  templateUrl: 'connector-configuration.component.html'
})
export class ConnectorConfigurationComponent implements OnInit {
  version: string = packageJson.version;
  monitoring$: Observable<ConnectorStatus>;
  feature: Feature;
  specifications: ConnectorSpecification[] = [];
  configurations: ConnectorConfiguration[];
  StatusEventTypes = StatusEventTypes;

  constructor(
    public bsModalService: BsModalService,
    public connectorConfigurationService: ConnectorConfigurationService,
    public alertService: AlertService,
    private sharedService: SharedService
  ) {}

  async ngOnInit() {
    // console.log('Running version', this.version);
    this.feature = await this.sharedService.getFeatures();
    if (!this.feature.userHasMappingAdminRole) {
      this.alertService.warning(
        "The configuration on this tab is not editable, as you don't have Mapping ADMIN permissions. Please assign Mapping ADMIN permissions to your user."
      );
    }
    this.specifications =
      await this.connectorConfigurationService.getConnectorSpecifications();
    this.connectorConfigurationService
      .getConnectorConfigurationsLive()
      .subscribe((confs) => {
        this.configurations = confs;
      });

    await this.loadData();
  }

  async refresh() {
    this.connectorConfigurationService.resetCache();
    await this.loadData();
  }
  async loadData(): Promise<void> {
    await this.connectorConfigurationService.startConnectorConfigurations();
  }

  async clickedReconnect2NotificationEndpoint() {
    const response1 = await this.sharedService.runOperation(
      Operation.REFRESH_NOTIFICATIONS_SUBSCRIPTIONS
    );
    // console.log('Details reconnect2NotificationEndpoint', response1);
    if (response1.status === 201) {
      this.alertService.success(gettext('Reconnected successfully.'));
    } else {
      this.alertService.danger(gettext('Failed to reconnect!'));
    }
  }

  async onConfigurationUpdate(index) {
    const configuration = this.configurations[index];

    const initialState = {
      add: false,
      configuration: configuration,
      specifications: this.specifications
    };
    const modalRef = this.bsModalService.show(
      ConfigurationConfigurationModalComponent,
      {
        initialState
      }
    );
    modalRef.content.closeSubject.subscribe(async (editedConfiguration) => {
      // console.log('Configuration after edit:', editedConfiguration);
      if (editedConfiguration) {
        this.configurations[index] = editedConfiguration;
        // avoid to include status$
        const clonedConfiguration = {
          ident: editedConfiguration.ident,
          connectorType: editedConfiguration.connectorType,
          enabled: editedConfiguration.enabled,
          name: editedConfiguration.name,
          properties: editedConfiguration.properties
        };
        const response =
          await this.connectorConfigurationService.updateConnectorConfiguration(
            clonedConfiguration
          );
        if (response.status < 300) {
          this.alertService.success(gettext('Updated successfully.'));
        } else {
          this.alertService.danger(
            gettext('Failed to update connector configuration')
          );
        }
      }
      await this.loadData();
    });
  }

  async onConfigurationCopy(index) {
    const configuration = _.clone(this.configurations[index]);
    configuration.ident = uuidCustom();
    configuration.name = `${configuration.name}_copy`;
    this.alertService.warning(
      gettext(
        'Review properties, e.g. client_id must be different across different client connectors to the same broker.'
      )
    );

    const initialState = {
      add: false,
      configuration: configuration,
      specifications: this.specifications
    };
    const modalRef = this.bsModalService.show(
      ConfigurationConfigurationModalComponent,
      {
        initialState
      }
    );
    modalRef.content.closeSubject.subscribe(async (editedConfiguration) => {
      // console.log('Configuration after edit:', editedConfiguration);
      if (editedConfiguration) {
        this.configurations[index] = editedConfiguration;
        // avoid to include status$
        const clonedConfiguration = {
          ident: editedConfiguration.ident,
          connectorType: editedConfiguration.connectorType,
          enabled: editedConfiguration.enabled,
          name: editedConfiguration.name,
          properties: editedConfiguration.properties
        };
        const response =
          await this.connectorConfigurationService.createConnectorConfiguration(
            clonedConfiguration
          );
        if (response.status < 300) {
          this.alertService.success(gettext('Updated successfully.'));
        } else {
          this.alertService.danger(
            gettext('Failed to update connector configuration!')
          );
        }
      }
      await this.loadData();
    });
  }

  async onConfigurationDelete(index) {
    const configuration = this.configurations[index];

    const initialState = {
      title: 'Delete connector',
      message: 'You are about to delete a connector. Do you want to proceed?',
      labels: {
        ok: 'Delete',
        cancel: 'Cancel'
      }
    };
    const confirmDeletionModalRef: BsModalRef = this.bsModalService.show(
      ConfirmationModalComponent,
      { initialState }
    );
    confirmDeletionModalRef.content.closeSubject.subscribe(
      async (result: boolean) => {
        // console.log('Confirmation result:', result);
        if (result) {
          const response =
            await this.connectorConfigurationService.deleteConnectorConfiguration(
              configuration.ident
            );
          if (response.status < 300) {
            this.alertService.success(gettext('Deleted successfully.'));
          } else {
            this.alertService.danger(
              gettext('Failed to delete connector configuration')
            );
          }
          await this.loadData();
        }
        confirmDeletionModalRef.hide();
      }
    );
    await this.loadData();
  }

  async onConfigurationToggle(index) {
    const configuration = this.configurations[index];
    const response1 = await this.sharedService.runOperation(
      configuration.enabled ? Operation.DISCONNECT : Operation.CONNECT,
      { connectorIdent: configuration.ident }
    );
    // console.log('Details toggle activation to broker', response1);
    if (response1.status === 201) {
      // if (response1.status === 201 && response2.status === 201) {
      this.alertService.success(gettext('Connection updated successfully.'));
    } else {
      this.alertService.danger(gettext('Failed to establish connection!'));
    }
    await this.loadData();
    this.sharedService.refreshMappings(Direction.INBOUND);
    this.sharedService.refreshMappings(Direction.OUTBOUND);
  }
}
