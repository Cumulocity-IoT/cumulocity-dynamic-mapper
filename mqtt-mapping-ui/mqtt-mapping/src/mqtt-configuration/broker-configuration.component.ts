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
import { FormControl, FormGroup, Validators } from "@angular/forms";
import { BrokerConfigurationService } from "./broker-configuration.service";
import { AlertService, gettext } from "@c8y/ngx-components";
import { BsModalRef, BsModalService } from "ngx-bootstrap/modal";
import { TerminateBrokerConnectionModalComponent } from "./terminate/terminate-connection-modal.component";
import { MappingService } from "../mqtt-mapping/core/mapping.service";
import { from, Observable } from "rxjs";
import { map } from "rxjs/operators";
import {
  ConnectionConfiguration as ConnectionConfiguration,
  ConnectorProperty,
  ConnectorPropertyConfiguration,
  Feature,
  Operation,
  ServiceConfiguration,
  ServiceStatus,
  Status,
} from "../shared/mapping.model";
import packageJson from "../../package.json";
import { FormlyFieldConfig } from "@ngx-formly/core";

@Component({
  selector: "mapping-broker-configuration",
  templateUrl: "broker-configuration.component.html",
})
export class BrokerConfigurationComponent implements OnInit {
  version: string = packageJson.version;
  isBrokerConnected: boolean;
  isConnectionEnabled: boolean;
  isBrokerAgentCreated$: Observable<boolean>;
  monitorings$: Observable<ServiceStatus>;
  subscription: any;
  connectionForm: FormGroup;
  serviceForm: FormGroup;
  feature: Feature;
  connectorId: String;
  specifications: ConnectorPropertyConfiguration[];

  connectionConfiguration: ConnectionConfiguration = {
    mqttHost: "",
    mqttPort: 0,
    user: "",
    password: "",
    clientId: "",
    useTLS: false,
    enabled: false,
    useSelfSignedCertificate: false,
    fingerprintSelfSignedCertificate: "",
    nameCertificate: "",
  };
  serviceConfiguration: ServiceConfiguration = {
    logPayload: true,
    logSubstitution: true,
  };

  constructor(
    public bsModalService: BsModalService,
    public configurationService: BrokerConfigurationService,
    public mappingService: MappingService,
    public alertservice: AlertService
  ) {}

  brokerFormlyFields: FormlyFieldConfig[] = [];
  brokerFormly: FormGroup = new FormGroup({});
  dynamicFormlyFields: FormlyFieldConfig[] = [];
  dynamicFormly: FormGroup = new FormGroup({});
  brokerConfig1Model: any = {};
  brokerConfig2Model: any = {};

  async ngOnInit() {
    console.log("Running version", this.version);
    this.initForms();
    await this.loadData();

    this.brokerFormlyFields = [
      {
        fieldGroupClassName: "row",
        fieldGroup: [
          {
            className: "col-lg-6",
            key: "connectorId",
            type: "select",
            wrappers: ["c8y-form-field"],
            templateOptions: {
              label: "Connector Id",
              options: this.specifications.map((sp) => {
                return {
                  label: sp.connectorId,
                  value: sp.connectorId,
                };
              }),
              change: (field: FormlyFieldConfig, event?: any) => {
                this.createDynamicForm(
                  this.brokerFormly.get("connectorId").value
                );
              },
              required: true,
            },
          },
        ],
      },
    ];
    this.initializeMonitoringService();
    this.isBrokerAgentCreated$ = from(
      this.configurationService.initializeBrokerAgent()
    )
      // .pipe(map(agentId => agentId != null), tap(() => this.initializeMonitoringService()));
      .pipe(map((agentId) => agentId != null));
    this.feature = await this.configurationService.getFeatures();
  }

  private async createDynamicForm(connectorId: string): Promise<void> {
    const dynamicFields: ConnectorPropertyConfiguration =
      this.specifications.find((c) => c.connectorId == connectorId);
    if (dynamicFields) {
      for (const key in dynamicFields.properties) {
        const property = dynamicFields.properties[key];
        if (property == ConnectorProperty.NUMERIC_PROPERTY) {
          this.dynamicFormlyFields.push({
            // fieldGroupClassName: "row",
            fieldGroup: [
              {
                className: "col-lg-6",
                key: key,
                type: "input",
                wrappers: ["c8y-form-field"],
                templateOptions: {
                  type: "number",
                  label: key,
                  required: true,
                },
              },
            ],
          });
        } else if (property == ConnectorProperty.STRING_PROPERTY) {
          this.dynamicFormlyFields.push({
            // fieldGroupClassName: "row",
            fieldGroup: [
              {
                className: "col-lg-6",
                key: key,
                type: "input",
                wrappers: ["c8y-form-field"],
                templateOptions: {
                  label: key,
                  required: true,
                },
              },
            ],
          });
        } else if (property == ConnectorProperty.SENSITIVE_STRING_PROPERTY) {
          this.dynamicFormlyFields.push({
            // fieldGroupClassName: "row",
            fieldGroup: [
              {
                className: "col-lg-6",
                key: key,
                type: "input",
                wrappers: ["c8y-form-field"],
                templateOptions: {
                  type: "password",
                  label: key,
                  required: true,
                },
              },
            ],
          });
        } else if (property == ConnectorProperty.BOOLEAN_PROPERTY) {
          this.dynamicFormlyFields.push({
            //fieldGroupClassName: "row",
            fieldGroup: [
              {
                className: "col-lg-6",
                key: key,
                type: "switch",
                wrappers: ["c8y-form-field"],
                templateOptions: {
                  label: key,
                  required: true,
                },
              },
            ],
          });
        }
      }
      this.dynamicFormlyFields = [...this.dynamicFormlyFields];
    }
  }

  private async initializeMonitoringService(): Promise<void> {
    this.subscription =
      await this.configurationService.subscribeMonitoringChannel();
    this.monitorings$ = this.configurationService.getCurrentServiceStatus();
    this.monitorings$.subscribe((status) => {
      this.isBrokerConnected = status.status === Status.CONNECTED;
      this.isConnectionEnabled =
        status.status === Status.ENABLED || status.status === Status.CONNECTED;
    });
  }

  async loadConnectionStatus(): Promise<void> {
    let status = await this.configurationService.getConnectionStatus();
    this.isBrokerConnected = status.status === Status.CONNECTED;
    this.isConnectionEnabled =
      status.status === Status.ENABLED || status.status === Status.CONNECTED;
    console.log("Retrieved status:", status, this.isBrokerConnected);
  }

  private initForms(): void {
    this.connectionForm = new FormGroup({
      mqttHost: new FormControl("", Validators.required),
      mqttPort: new FormControl("", Validators.required),
      user: new FormControl(""),
      password: new FormControl(""),
      clientId: new FormControl("", Validators.required),
      useTLS: new FormControl(""),
      useSelfSignedCertificate: new FormControl(""),
      nameCertificate: new FormControl(""),
    });
    this.serviceForm = new FormGroup({
      logPayload: new FormControl(""),
      logSubstitution: new FormControl(""),
    });
  }

  private async loadData(): Promise<void> {
    let conn = await this.configurationService.getConnectionConfiguration();
    let conf = await this.configurationService.getServiceConfiguration();
    this.specifications =
      await this.configurationService.getConnectorSpecifications();
    console.log("Configuration:", conn, conf, this.specifications);
    if (conn) {
      this.connectionConfiguration = conn;
      this.isConnectionEnabled = conn.enabled;
    }

    if (conf) {
      this.serviceConfiguration = conf;
    }
  }

  async clickedConnect() {
    this.connectToBroker();
  }

  async clickedDisconnect() {
    this.showTerminateConnectionModal();
  }

  async clickedSaveConnectionConfiguration() {
    this.updateConnectionConfiguration();
  }

  async clickedSaveServiceConfiguration() {
    this.updateServiceConfiguration();
  }

  async clickedReconnect2NotificationEnpoint() {
    const response1 = await this.configurationService.runOperation(
      Operation.REFRESH_NOTFICATIONS_SUBSCRIPTIONS,
      { connectorId: this.connectorId }
    );
    console.log("Details reconnect2NotificationEnpoint", response1);
    if (response1.status === 201) {
      this.alertservice.success(gettext("Reconnect successful!"));
    } else {
      this.alertservice.danger(gettext("Failed to reconnect."));
    }
  }

  private async updateConnectionConfiguration() {
    let conn: ConnectionConfiguration = {
      ...this.connectionConfiguration,
      enabled: false,
    };
    const response =
      await this.configurationService.updateConnectionConfiguration(conn);
    if (response.status < 300) {
      this.alertservice.success(gettext("Update successful."));
    } else {
      this.alertservice.danger(gettext("Failed to update connection"));
    }
  }

  private async updateServiceConfiguration() {
    let conf: ServiceConfiguration = {
      ...this.serviceConfiguration,
    };
    const response = await this.configurationService.updateServiceConfiguration(
      conf
    );
    if (response.status < 300) {
      this.alertservice.success(gettext("Update successful"));
    } else {
      this.alertservice.danger(
        gettext("Failed to update service configuration")
      );
    }
  }

  private async connectToBroker() {
    const response1 = await this.configurationService.runOperation(
      Operation.CONNECT,
      { connectorId: this.connectorId }
    );
    //const response2 = await this.mappingService.activateMappings();
    //console.log("Details connectToBroker", response1, response2)
    console.log("Details connectToBroker", response1);
    if (response1.status === 201) {
      // if (response1.status === 201 && response2.status === 201) {
      this.alertservice.success(gettext("Connection successful"));
    } else {
      this.alertservice.danger(gettext("Failed to establish connection"));
    }
  }

  private showTerminateConnectionModal() {
    const terminateExistingConnectionModalRef: BsModalRef =
      this.bsModalService.show(TerminateBrokerConnectionModalComponent, {});
    terminateExistingConnectionModalRef.content.closeSubject.subscribe(
      async (isTerminateConnection: boolean) => {
        console.log("Termination result:", isTerminateConnection);
        if (!isTerminateConnection) {
        } else {
          await this.disconnectFromBroker();
        }
        terminateExistingConnectionModalRef.hide();
      }
    );
  }

  private async disconnectFromBroker() {
    const res = await this.configurationService.runOperation(
      Operation.DISCONNECT,
      { connectorId: this.connectorId }
    );
    console.log("Details disconnectFromMQTT", res);
    if (res.status < 300) {
      this.alertservice.success(gettext("Successfully disconnected"));
    } else {
      this.alertservice.danger(gettext("Failed to disconnect"));
    }
  }

  public async resetStatusMapping() {
    const res = await this.configurationService.runOperation(
      Operation.RESET_STATUS_MAPPING
    );
    if (res.status < 300) {
      this.alertservice.success(gettext("Successfully rreset"));
    } else {
      this.alertservice.danger(gettext("Failed to rest statistic."));
    }
  }

  ngOnDestroy(): void {
    console.log("Stop subscription");
    this.configurationService.unsubscribeFromMonitoringChannel(
      this.subscription
    );
  }
}
