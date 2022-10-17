import { Component, OnInit } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { BrokerConfigurationService } from './broker-configuration.service';
import { AlertService, gettext } from '@c8y/ngx-components';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { TerminateBrokerConnectionModalComponent } from './terminate/terminate-connection-modal.component';
import { MappingService } from '../mqtt-mapping/shared/mapping.service';
import { from, Observable } from 'rxjs';
import { map, tap } from 'rxjs/operators';
import { MQTTAuthentication, ServiceStatus, Status } from '../shared/configuration.model';

import packageJson from '../../package.json';

@Component({
  selector: 'broker-configuration',
  templateUrl: 'broker-configuration.component.html',
})
export class BokerConfigurationComponent implements OnInit {

  version: string = packageJson.version;
  isBrokerConnected: boolean;
  isBrokerActivated: boolean;
  isBrokerAgentCreated$: Observable<boolean>;
  monitorings$: Observable<ServiceStatus>;
  subscription: any;
  mqttForm: FormGroup;
  configuration: MQTTAuthentication = {
    mqttHost: '',
    mqttPort: 0,
    user: '',
    password: '',
    clientId: '',
    useTLS: false,
    active: false,
  };

  constructor(
    private bsModalService: BsModalService,
    public configurationService: BrokerConfigurationService,
    public mappingService: MappingService,
    public alertservice: AlertService
  ) {
  }

  ngOnInit() {
    console.log("Running version", this.version);
    this.initForm();
    this.loadConnectionDetails();
    this.isBrokerAgentCreated$ = from(this.configurationService.initializeMQTTAgent())
        .pipe(map(agentId => agentId != null), tap(() => this.initializeMonitoringService()));
  }


  private async initializeMonitoringService(): Promise<void> {
    this.subscription = await this.configurationService.subscribeMonitoringChannel();
    this.monitorings$ = this.configurationService.getCurrentServiceStatus();
    this.monitorings$.subscribe(status => {
      this.isBrokerConnected = (status.status === Status.CONNECTED);
      this.isBrokerActivated = (status.status === Status.ACTIVATED || status.status === Status.CONNECTED);
    })
  }

  async loadConnectionStatus(): Promise<void> {
    let status = await this.configurationService.getConnectionStatus();
    this.isBrokerConnected = (status.status === Status.CONNECTED);
    this.isBrokerActivated = (status.status === Status.ACTIVATED || status.status === Status.CONNECTED);
    console.log("Retrieved status:", status, this.isBrokerConnected)
  }

  private initForm(): void {
    this.mqttForm = new FormGroup({
      mqttHost: new FormControl('', Validators.required),
      mqttPort: new FormControl('', Validators.required),
      user: new FormControl('', Validators.required),
      password: new FormControl('', Validators.required),
      clientId: new FormControl('', Validators.required),
      active: new FormControl('', Validators.required),
      useTLS: new FormControl('', Validators.required),
    });
  }

  private async loadConnectionDetails(): Promise<void> {
    let conf = await this.configurationService.getConnectionDetails();
    console.log("Connection details", this.configuration)
    if (conf) {
      this.configuration = conf;
      this.isBrokerActivated = conf.active;
    }
  }

  async onConnectButtonClicked() {
    this.connectToMQTTBroker();
  }

  async onDisconnectButtonClicked() {
    this.showTerminateConnectionModal();
  }

  async onUpdateButtonClicked() {
    this.updateConnectionDetails();
  }

  private async updateConnectionDetails() {
    let conf: MQTTAuthentication = {
      ...this.configuration,
      active: false
    }
    const response = await this.configurationService.updateConnectionDetails(conf);

    if (response.status < 300) {
      this.alertservice.success(gettext('Update successful'));
    } else {
      this.alertservice.danger(gettext('Failed to update connection'));
    }
  }

  private async connectToMQTTBroker() {
    const response1 = await this.configurationService.connectToMQTTBroker();
    //const response2 = await this.mappingService.activateMappings();
    //console.log("Details connectToMQTTBroker", response1, response2)
    console.log("Details connectToMQTTBroker", response1)
    if (response1.status === 201) {
    // if (response1.status === 201 && response2.status === 201) {
      this.alertservice.success(gettext('Connection successful'));
    } else {
      this.alertservice.danger(gettext('Failed to establish connection'));
    }
  }

  private showTerminateConnectionModal() {
    const terminateExistingConnectionModalRef: BsModalRef = this.bsModalService.show(
      TerminateBrokerConnectionModalComponent,
      {}
    );
    terminateExistingConnectionModalRef.content.closeSubject.subscribe(
      async (isTerminateConnection: boolean) => {
        if (!isTerminateConnection) {
          return;
        }
        await this.disconnectFromMQTT();
      }
    );
  }

  private async disconnectFromMQTT() {
    const response = await this.configurationService.disconnectFromMQTTBroker();
    console.log("Details disconnectFromMQTT", response)
    if (response.status < 300) {
      this.alertservice.success(gettext('Successfully disconnected'));
    } else {
      this.alertservice.danger(gettext('Failed to disconnect'));
    }
  }

  ngOnDestroy(): void {
    console.log("Stop subscription");
    this.configurationService.unsubscribeFromMonitoringChannel(this.subscription);
  }
}
