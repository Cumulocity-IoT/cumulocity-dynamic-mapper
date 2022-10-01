import { Component, OnInit } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { BrokerConfigurationService } from './broker-configuration.service';
import { AlertService, gettext } from '@c8y/ngx-components';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { TerminateBrokerConnectionModalComponent } from './terminate/terminate-connection-modal.component';
import { MappingService } from '../mqtt-mapping/shared/mapping.service';
import { from, Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { ServiceStatus, Status } from '../shared/configuration.model';


@Component({
  selector: 'broker-configuration',
  templateUrl: 'broker-configuration.component.html',
})
export class BokerConfigurationComponent implements OnInit {

  isMQTTConnected: boolean;
  isMQTTAgentCreated$: Observable<boolean>;
  mqttAgentId$: Observable<string>;
  monitorings$: Observable<ServiceStatus>;
  subscription: object;
  mqttForm: FormGroup;

  constructor(
    private bsModalService: BsModalService,
    public configurationService: BrokerConfigurationService,
    public mappingService: MappingService,
    public alertservice: AlertService
  ) {
  }

  ngOnInit() {
    this.initForm();
    this.initializeMonitoringService();
    this.initConnectionDetails();
    this.mqttAgentId$ = from(this.configurationService.initializeMQTTAgent());
    this.isMQTTAgentCreated$ = this.mqttAgentId$.pipe(map(agentId => agentId != null));
    //console.log("Init configuration, mqttAgent", this.isMQTTAgentCreated);
  }


  private async initializeMonitoringService(): Promise<void> {
    this.subscription = await this.configurationService.subscribeMonitoringChannel();
    this.monitorings$ = this.configurationService.getCurrentServiceStatus();
    this.monitorings$.subscribe(status => {
      if (status.status === Status.ACTIVATED) {
        this.isMQTTConnected = false;
      } else if (status.status === Status.CONNECTED) {
        this.isMQTTConnected = true;
      } else if (status.status === Status.CONFIGURED) {
        this.isMQTTConnected = false;
      }
    })
  }

  async initConnectionStatus(): Promise<void> {

    this.isMQTTConnected = false;
    let status = await this.configurationService.getConnectionStatus();
    if (status === "ACTIVATED") {
      this.isMQTTConnected = false;

    } else if (status === "CONNECTED") {
      this.isMQTTConnected = true;

    } else if (status === "ONLY_CONFIGURED") {
      this.isMQTTConnected = false;
    }
    console.log("Retrieved status:", status, this.isMQTTConnected)
  }

  private initForm(): void {
    this.mqttForm = new FormGroup({
      mqttHost: new FormControl('', Validators.required),
      mqttPort: new FormControl('', Validators.required),
      user: new FormControl('', Validators.required),
      password: new FormControl('', Validators.required),
      clientId: new FormControl('', Validators.required),
      useTLS: new FormControl('', Validators.required),
    });
  }

  private async initConnectionDetails(): Promise<void> {
    const connectionDetails = await this.configurationService.getConnectionDetails();
    console.log("Connection details", connectionDetails)
    if (!connectionDetails) {
      return;
    }

    this.mqttForm.patchValue({
      mqttHost: connectionDetails.mqttHost,
      mqttPort: connectionDetails.mqttPort,
      user: connectionDetails.user,
      password: connectionDetails.password,
      clientId: connectionDetails.clientId,
      useTLS: connectionDetails.useTLS,
    });
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
    const response = await this.configurationService.updateConnectionDetails({
      mqttHost: this.mqttForm.value.mqttHost,
      mqttPort: this.mqttForm.value.mqttPort,
      user: this.mqttForm.value.user,
      password: this.mqttForm.value.password,
      clientId: this.mqttForm.value.clientId,
      useTLS: this.mqttForm.value.useTLS,
      active: false
    });

    if (response.status < 300) {
      this.alertservice.success(gettext('Update successful'));
    } else {
      this.alertservice.danger(gettext('Failed to update connection'));
    }
  }

  private async connectToMQTTBroker() {
    const response1 = await this.configurationService.connectToMQTTBroker();
    const response2 = await this.mappingService.activateMappings();
    console.log("Details connectToMQTTBroker", response1, response2)
    if (response1.status === 201 && response2.status === 201) {
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
