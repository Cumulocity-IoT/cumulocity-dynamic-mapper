import { Injectable } from '@angular/core';
import { FetchClient, IdentityService, IExternalIdentity, IFetchResponse } from '@c8y/client';
import { MQTTAuthentication } from '../mqtt-configuration.model';

@Injectable({ providedIn: 'root' })
export class MQTTConfigurationService {
  private readonly PATH_CONNECT_ENDPOINT = 'connection';

  private readonly PATH_STATUS_ENDPOINT = 'status';


  private readonly PATH_OPERATION_ENDPOINT = 'operation';

  private readonly BASE_URL = 'service/generic-mqtt-agent';

  private isMQTTAgentCreated = false;

  constructor(private client: FetchClient,
    private identity: IdentityService,) { }

  async initializeMQTTAgent(): Promise<void> {
    const identity: IExternalIdentity = {
      type: 'c8y_Serial',
      externalId: 'MQTT_AGENT'
    };

    try {
      const { data, res } = await this.identity.detail(identity);
      console.log("Configuration result code: {}", res.status);
      this.isMQTTAgentCreated = true;

    } catch (error) {
      console.error("Configuration result code: {}", error);
      this.isMQTTAgentCreated = false;
    }
  }


  getMQTTAgentCreated(): boolean {
    return this.isMQTTAgentCreated;
  }

  updateConnectionDetails(mqttConfiguration: MQTTAuthentication): Promise<IFetchResponse> {
    return this.client.fetch(`${this.BASE_URL}/${this.PATH_CONNECT_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify(mqttConfiguration),
      method: 'POST',
    });
  }

  connect(): Promise<IFetchResponse> {
    return this.client.fetch(`${this.BASE_URL}/${this.PATH_OPERATION_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({"operation": "CONNECT"}),
      method: 'POST',
    });
  }

  disconnect(): Promise<IFetchResponse> {
    return this.client.fetch(`${this.BASE_URL}/${this.PATH_OPERATION_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({"operation": "DISCONNECT"}),
      method: 'POST',
    });
  }

  async getConnectionDetails(): Promise<MQTTAuthentication> {
    const response = await this.client.fetch(`${this.BASE_URL}/${this.PATH_CONNECT_ENDPOINT}`, {
      headers: {
        accept: 'application/json',
      },
      method: 'GET',
    });

    if (response.status != 200) {
      return undefined;
    }

    return (await response.json()) as MQTTAuthentication;
  }

  async getConnectionStatus(): Promise<string> {
    const response = await this.client.fetch(`${this.BASE_URL}/${this.PATH_STATUS_ENDPOINT}`, {
      method: 'GET',
    });
    const { status } = await response.json();
    return status;
  }

}
