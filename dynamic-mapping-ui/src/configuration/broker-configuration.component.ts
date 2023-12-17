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
import { Observable, from } from "rxjs";
import { map } from "rxjs/operators";
import packageJson from "../../package.json";
import {
  ConfirmationModalComponent,
  ConnectorConfiguration,
  ConnectorConfigurationCombined,
  ConnectorSpecification,
  ConnectorStatus,
  Feature,
  Operation,
  ServiceConfiguration,
  uuidCustom,
} from "../shared";
import { BrokerConfigurationService } from "./shared/broker-configuration.service";
import { EditConfigurationComponent } from "./edit/edit-config-modal.component";

@Component({
  selector: "d11r-mapping-broker-configuration",
  templateUrl: "broker-configuration.component.html",
})
export class BrokerConfigurationComponent implements OnInit {
  version: string = packageJson.version;
  isBrokerConnected: boolean;
  isConnectionEnabled: boolean;
  isBrokerAgentCreated$: Observable<boolean> = new Observable();
  monitorings$: Observable<ConnectorStatus>;
  subscription: any;
  serviceForm: FormGroup;
  feature: Feature;
  specifications: ConnectorSpecification[] = [];
  configurations: ConnectorConfigurationCombined[] = [];

  serviceConfiguration: ServiceConfiguration = {
    logPayload: true,
    logSubstitution: true,
    logErrorConnect: false,
  };

  constructor(
    public bsModalService: BsModalService,
    public brokerConfigurationService: BrokerConfigurationService,
    public alert: AlertService
  ) {}

  async ngOnInit() {
    console.log("Running version", this.version);
    this.initForms();
    await this.loadData();

    this.initializeMonitoringService();
    this.isBrokerAgentCreated$ = from(
      this.brokerConfigurationService.getDynamicMappingServiceAgent()
    )
      // .pipe(map(agentId => agentId != null), tap(() => this.initializeMonitoringService()));
      .pipe(map((agentId) => agentId != null));
    this.feature = await this.brokerConfigurationService.getFeatures();
  }

  private async initializeMonitoringService(): Promise<void> {
    this.subscription =
      await this.brokerConfigurationService.subscribeMonitoringChannel();
  }

  private initForms(): void {
    this.serviceForm = new FormGroup({
      logPayload: new FormControl(""),
      logSubstitution: new FormControl(""),
      logErrorConnect: new FormControl(""),
    });
  }

  public async loadData(): Promise<void> {
    this.serviceConfiguration = await this.brokerConfigurationService.getServiceConfiguration();
    this.specifications =
      await this.brokerConfigurationService.getConnectorSpecifications();
    this.configurations =
      await this.brokerConfigurationService.getConnectorConfigurationsCombined();
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
    const configuration = this.configurations[index].configuration;

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
        const response =
          await this.brokerConfigurationService.updateConnectorConfiguration(
            editedConfiguration
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
    const configuration = this.configurations[index].configuration;

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
        const response =
          await this.brokerConfigurationService.createConnectorConfiguration(
            addedConfiguration
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
      configuration.configuration.enabled
        ? Operation.DISCONNECT
        : Operation.CONNECT,
      { connectorIdent: configuration.configuration.ident }
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

  ngOnDestroy(): void {
    console.log("Stop subscription");
    this.brokerConfigurationService.unsubscribeFromMonitoringChannel(
      this.subscription
    );
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
}
