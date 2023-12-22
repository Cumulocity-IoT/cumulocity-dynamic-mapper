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
import { Component, OnInit } from "@angular/core";
import { FormControl, FormGroup } from "@angular/forms";
import { AlertService, gettext } from "@c8y/ngx-components";
import { BsModalRef, BsModalService } from "ngx-bootstrap/modal";
import { Observable } from "rxjs";
import packageJson from "../../package.json";
import { ConfirmationModalComponent, uuidCustom } from "../shared";
import { BrokerConfigurationService } from "./shared/broker-configuration.service";
import { EditConfigurationComponent } from "./edit/edit-config-modal.component";
import {
  ConnectorConfiguration,
  ConnectorSpecification,
  ConnectorStatus,
  Feature,
  Operation,
  ServiceConfiguration,
  StatusEventTypes,
} from "./shared/configuration.model";

@Component({
  selector: "d11r-mapping-broker-configuration",
  templateUrl: "broker-configuration.component.html",
})
export class BrokerConfigurationComponent implements OnInit {
  version: string = packageJson.version;
  monitorings$: Observable<ConnectorStatus>;
  serviceForm: FormGroup;
  feature: Feature;
  specifications: ConnectorSpecification[] = [];
  configurations: ConnectorConfiguration[];
  statusLogs$: Observable<any[]>;
  statusLogEventType: string = StatusEventTypes.STATUS_CONNECTOR_EVENT_TYPE;
  StatusEventTypes = StatusEventTypes;

  serviceConfiguration: ServiceConfiguration = {
    logPayload: true,
    logSubstitution: true,
    logConnectorErrorInBackend: false,
    sendConnectorLifecycle: false,
    sendMappingStatus: false,
    sendSubscriptionEvents: false,
  };

  constructor(
    public bsModalService: BsModalService,
    public brokerConfigurationService: BrokerConfigurationService,
    public alert: AlertService
  ) {}

  async ngOnInit() {
    console.log("Running version", this.version);
    this.serviceForm = new FormGroup({
      logPayload: new FormControl(""),
      logSubstitution: new FormControl(""),
      logConnectorErrorInBackend: new FormControl(""),
      sendConnectorLifecycle: new FormControl(""),
      sendMappingStatus: new FormControl(""),
      sendSubscriptionEvents: new FormControl(""),
    });
    await this.loadData();
    this.statusLogs$ = this.brokerConfigurationService.getStatusLogs();
    this.feature = await this.brokerConfigurationService.getFeatures();
  }

  public async refresh() {
    this.brokerConfigurationService.resetCache();
    await this.loadData();
  }

  public async loadData(): Promise<void> {
    this.configurations =
      await this.brokerConfigurationService.getConnectorConfigurationsWithStatus();
    this.serviceConfiguration =
      await this.brokerConfigurationService.getServiceConfiguration();
    this.specifications =
      await this.brokerConfigurationService.getConnectorSpecifications();
  }

  async clickedReconnect2NotificationEnpoint() {
    const response1 = await this.brokerConfigurationService.runOperation(
      Operation.REFRESH_NOTFICATIONS_SUBSCRIPTIONS
    );
    console.log("Details reconnect2NotificationEnpoint", response1);
    if (response1.status === 201) {
      this.alert.success(gettext("Reconnect successful!"));
    } else {
      this.alert.danger(gettext("Failed to reconnect."));
    }
  }

  public async onConfigurationUpdate(index) {
    const configuration = this.configurations[index];

    const initialState = {
      add: false,
      configuration: configuration,
      specifications: this.specifications,
    };
    const modalRef = this.bsModalService.show(EditConfigurationComponent, {
      initialState,
    });
    modalRef.content.closeSubject.subscribe(async (editedConfiguration) => {
      console.log("Configuration after edit:", editedConfiguration);
      if (editedConfiguration) {
        this.configurations[index] = editedConfiguration;
        //avoid to include status$
        const clonedConfiguration = {
          ident: editedConfiguration.ident,
          connectorType: editedConfiguration.connectorType,
          enabled: editedConfiguration.enabled,
          name: editedConfiguration.name,
          properties: editedConfiguration.properties,
        };
        const response =
          await this.brokerConfigurationService.updateConnectorConfiguration(
            clonedConfiguration
          );
        if (response.status < 300) {
          this.alert.success(gettext("Update successful"));
        } else {
          this.alert.danger(
            gettext("Failed to update connector configuration")
          );
        }
        await this.loadData();
      }
    });
  }

  public async onConfigurationDelete(index) {
    const configuration = this.configurations[index];

    const initialState = {
      title: "Delete connector",
      message: "You are about to delete a connector. Do you want to proceed?",
      labels: {
        ok: "Delete",
        cancel: "Cancel",
      },
    };
    const confirmDeletionModalRef: BsModalRef = this.bsModalService.show(
      ConfirmationModalComponent,
      { initialState }
    );
    confirmDeletionModalRef.content.closeSubject.subscribe(
      async (result: boolean) => {
        console.log("Confirmation result:", result);
        if (!!result) {
          const response =
            await this.brokerConfigurationService.deleteConnectorConfiguration(
              configuration.ident
            );
          if (response.status < 300) {
            this.alert.success(gettext("Deleted successful"));
          } else {
            this.alert.danger(
              gettext("Failed to delete connector configuration")
            );
          }
          await this.loadData();
        }
        confirmDeletionModalRef.hide();
      }
    );
  }

  public async onConfigurationAdd() {
    const configuration: Partial<ConnectorConfiguration> = {
      properties: {},
      ident: uuidCustom(),
    };
    const initialState = {
      add: true,
      configuration: configuration,
      specifications: this.specifications,
    };
    const modalRef = this.bsModalService.show(EditConfigurationComponent, {
      initialState,
    });
    modalRef.content.closeSubject.subscribe(async (addedConfiguration) => {
      console.log("Configuration after edit:", addedConfiguration);
      if (addedConfiguration) {
        this.configurations.push(addedConfiguration);
        //avoid to include status$
        const clonedConfiguration = {
          ident: addedConfiguration.ident,
          connectorType: addedConfiguration.connectorType,
          enabled: addedConfiguration.enabled,
          name: addedConfiguration.name,
          properties: addedConfiguration.properties,
        };
        const response =
          await this.brokerConfigurationService.createConnectorConfiguration(
            clonedConfiguration
          );
        if (response.status < 300) {
          this.alert.success(gettext("Added successfully configuration"));
        } else {
          this.alert.danger(
            gettext("Failed to update connector configuration")
          );
        }
        await this.loadData();
      }
    });
  }

  public async onConfigurationToogle(index) {
    const configuration = this.configurations[index];
    const response1 = await this.brokerConfigurationService.runOperation(
      configuration.enabled ? Operation.DISCONNECT : Operation.CONNECT,
      { connectorIdent: configuration.ident }
    );
    console.log("Details toogle activation to broker", response1);
    if (response1.status === 201) {
      // if (response1.status === 201 && response2.status === 201) {
      this.alert.success(gettext("Connection updated successful"));
    } else {
      this.alert.danger(gettext("Failed to establish connection"));
    }
    await this.loadData();
  }

  public async resetStatusMapping() {
    const res = await this.brokerConfigurationService.runOperation(
      Operation.RESET_STATUS_MAPPING
    );
    if (res.status < 300) {
      this.alert.success(gettext("Successfully reset"));
    } else {
      this.alert.danger(gettext("Failed to rest statistic."));
    }
  }

  async clickedSaveServiceConfiguration() {
    let conf: ServiceConfiguration = {
      ...this.serviceConfiguration,
    };
    const response =
      await this.brokerConfigurationService.updateServiceConfiguration(conf);
    if (response.status < 300) {
      this.alert.success(gettext("Update successful"));
    } else {
      this.alert.danger(gettext("Failed to update service configuration"));
    }
  }

  updateStatusLogs() {
    this.brokerConfigurationService.updateStatusLogs(this.statusLogEventType);
  }

  ngOnDestroy(): void {
    console.log("Stop subscriptions");
  }
}
