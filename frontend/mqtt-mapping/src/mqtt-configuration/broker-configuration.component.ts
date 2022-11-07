import { Component, OnInit } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { BrokerConfigurationService } from './broker-configuration.service';
import { AlertService, gettext } from '@c8y/ngx-components';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { TerminateBrokerConnectionModalComponent } from './terminate/terminate-connection-modal.component';
import { MappingService } from '../mqtt-mapping/shared/mapping.service';
import { from, Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { ConnectionConfiguration as ConnectionConfiguration, Operation, ServiceConfiguration, ServiceStatus, Status } from '../shared/configuration.model';
import packageJson from '../../package.json';

@Component({
  selector: 'broker-configuration',
  templateUrl: 'broker-configuration.component.html',
})
export class BokerConfigurationComponent implements OnInit {

  version: string = packageJson.version;
  isBrokerConnected: boolean;
  isConnectionEnabled: boolean;
  isBrokerAgentCreated$: Observable<boolean>;
  monitorings$: Observable<ServiceStatus>;
  subscription: any;
  connectionForm: FormGroup;
  serviceForm: FormGroup;

  connectionConfiguration: ConnectionConfiguration = {
    mqttHost: '',
    mqttPort: 0,
    user: '',
    password: '',
    clientId: '',
    useTLS: false,
    enabled: false,
    useSelfSignedCertificate: false,
    fingerprintSelfSignedCertificate: '',
    nameCertificate: ''
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
  ) {
  }

  ngOnInit() {
    console.log("Running version", this.version);
    this.initForms();
    this.loadData();
    this.initializeMonitoringService();
    this.isBrokerAgentCreated$ = from(this.configurationService.initializeMQTTAgent())
    // .pipe(map(agentId => agentId != null), tap(() => this.initializeMonitoringService()));
    .pipe(map(agentId => agentId != null));
  }


  private async initializeMonitoringService(): Promise<void> {
    this.subscription = await this.configurationService.subscribeMonitoringChannel();
    this.monitorings$ = this.configurationService.getCurrentServiceStatus();
    this.monitorings$.subscribe(status => {
      this.isBrokerConnected = (status.status === Status.CONNECTED);
      this.isConnectionEnabled = (status.status === Status.ENABLED || status.status === Status.CONNECTED);
    })
  }

  async loadConnectionStatus(): Promise<void> {
    let status = await this.configurationService.getConnectionStatus();
    this.isBrokerConnected = (status.status === Status.CONNECTED);
    this.isConnectionEnabled = (status.status === Status.ENABLED || status.status === Status.CONNECTED);
    console.log("Retrieved status:", status, this.isBrokerConnected)
  }

  private initForms(): void {
    this.connectionForm = new FormGroup({
      mqttHost: new FormControl('', Validators.required),
      mqttPort: new FormControl('', Validators.required),
      user: new FormControl('', Validators.required),
      password: new FormControl('', Validators.required),
      clientId: new FormControl('', Validators.required),
      useTLS: new FormControl(''),
      useSelfSignedCertificate: new FormControl(''),
      nameCertificate: new FormControl(''),
    });
    this.serviceForm = new FormGroup({
      logPayload: new FormControl(''),
      logSubstitution: new FormControl(''),
    });
  }

  private async loadData(): Promise<void> {
    let conn = await this.configurationService.getConnectionConfiguration();
    let conf = await this.configurationService.getServiceConfiguration();
    console.log("Configuration:", conn, conf)
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

  private async updateConnectionConfiguration() {
    let conn: ConnectionConfiguration = {
      ...this.connectionConfiguration,
      enabled: false
    }
    const response = await this.configurationService.updateConnectionConfiguration(conn);
    if (response.status < 300) {
      this.alertservice.success(gettext('Update successful'));
    } else {
      this.alertservice.danger(gettext('Failed to update connection'));
    }
  }

  private async updateServiceConfiguration() {
    let conf: ServiceConfiguration = {
      ...this.serviceConfiguration,
    }
    const response = await this.configurationService.updateServiceConfiguration(conf);
    if (response.status < 300) {
      this.alertservice.success(gettext('Update successful'));
    } else {
      this.alertservice.danger(gettext('Failed to update service configuration'));
    }
  }

  private async connectToBroker() {
    const response1 = await this.configurationService.runOperation(Operation.CONNECT);
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
    const response = await this.configurationService.runOperation(Operation.DISCONNECT);
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
