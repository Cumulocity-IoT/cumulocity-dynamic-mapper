import { Injectable } from '@angular/core';
import { FetchClient, IdentityService, IExternalIdentity, IFetchResponse, IManagedObject, InventoryService, IResult, Realtime } from '@c8y/client';
import { AGENT_ID, BASE_URL, MQTT_TEST_DEVICE_ID, MQTT_TEST_DEVICE_TYPE, PATH_CONFIGURATION_CONNECTION_ENDPOINT, PATH_CONFIGURATION_SERVICE_ENDPOINT, PATH_OPERATION_ENDPOINT, PATH_STATUS_ENDPOINT, STATUS_SERVICE_EVENT_TYPE } from '../shared/helper';
import { ConnectionConfiguration, Operation, ServiceConfiguration, ServiceStatus, Status } from '../shared/configuration.model';
import { BehaviorSubject, Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class BrokerConfigurationService {
  constructor(private client: FetchClient,
    private identity: IdentityService,
    private inventory: InventoryService,) {
    this.realtime = new Realtime(this.client);
  }

  private agentId: string;
  private testDeviceId: string;
  private serviceStatus = new BehaviorSubject<ServiceStatus>({ status: Status.NOT_READY });
  private _currentServiceStatus = this.serviceStatus.asObservable();
  private realtime: Realtime

  async initializeMQTTAgent(): Promise<string> {
    if (!this.agentId) {
      const identity: IExternalIdentity = {
        type: 'c8y_Serial',
        externalId: AGENT_ID
      };

      const { data, res } = await this.identity.detail(identity);
      if (res.status < 300) {
        this.agentId = data.managedObject.id.toString();
        console.log("MQTTConfigurationService: Found MQTTAgent", this.agentId);
      }
    }
    return this.agentId;
  }

  async initializeTestDevice(): Promise<string> {
    if (!this.testDeviceId) {
      let identity: IExternalIdentity = {
        type: 'c8y_Serial',
        externalId: MQTT_TEST_DEVICE_ID
      };

      try {
        const { data, res } = await this.identity.detail(identity);
        this.testDeviceId = data.managedObject.id.toString();
        console.log("MQTTConfigurationService: Found MQTT Test Device", this.testDeviceId);
        return this.testDeviceId;
      } catch (e) {
        console.log("So far no test device generated!")
        // create new mapping mo
        const response: IResult<IManagedObject> = await this.inventory.create({
          name: "MQTT Mapping Test Device",
          type: MQTT_TEST_DEVICE_TYPE,
          c8y_IsDevice: {},
          com_cumulocity_model_Agent: {}
        });

        //create identity for mo
        identity = {
          ...identity,
          managedObject: {
            id: response.data.id
          }
        }
        const { data, res } = await this.identity.create(identity);
      }
      return this.testDeviceId;
    }
  }

  updateConnectionConfiguration(configuration: ConnectionConfiguration): Promise<IFetchResponse> {
    return this.client.fetch(`${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify(configuration),
      method: 'POST',
    });
  }

  updateServiceConfiguration(configuration: ServiceConfiguration): Promise<IFetchResponse> {
    return this.client.fetch(`${BASE_URL}/${PATH_CONFIGURATION_SERVICE_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify(configuration),
      method: 'POST',
    });
  }

  async getConnectionConfiguration(): Promise<ConnectionConfiguration> {
    const response = await this.client.fetch(`${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}`, {
      headers: {
        accept: 'application/json',
      },
      method: 'GET',
    });

    if (response.status != 200) {
      return undefined;
    }

    return (await response.json()) as ConnectionConfiguration;
  }

  async getServiceConfiguration(): Promise<ServiceConfiguration> {
    const response = await this.client.fetch(`${BASE_URL}/${PATH_CONFIGURATION_SERVICE_ENDPOINT}`, {
      headers: {
        accept: 'application/json',
      },
      method: 'GET',
    });

    if (response.status != 200) {
      return undefined;
    }

    return (await response.json()) as ServiceConfiguration;
  }

  async getConnectionStatus(): Promise<ServiceStatus> {
    const response = await this.client.fetch(`${BASE_URL}/${PATH_STATUS_ENDPOINT}`, {
      method: 'GET',
    });
    const result = await response.json();
    return result;
  }

  public getCurrentServiceStatus(): Observable<ServiceStatus> {
    return this._currentServiceStatus;
  }

  async subscribeMonitoringChannel(): Promise<object> {
    this.agentId = await this.initializeMQTTAgent();
    console.log("Started subscription:", this.agentId);
    this.getConnectionStatus().then(status => {
      this.serviceStatus.next(status);
    })
    return this.realtime.subscribe(`/managedobjects/${this.agentId}`, this.updateStatus.bind(this));
  }

  unsubscribeFromMonitoringChannel(subscription: object): object {
    return this.realtime.unsubscribe(subscription);
  }

  private updateStatus(p: object): void {
    let payload = p['data']['data'];
    let status: ServiceStatus = payload['service_status'];
    this.serviceStatus.next(status);
    //console.log("New monitoring event", status);
  }

  runOperation(op: Operation): Promise<IFetchResponse> {
    return this.client.fetch(`${BASE_URL}/${PATH_OPERATION_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({ "operation": op }),
      method: 'POST',
    });
  }
}
