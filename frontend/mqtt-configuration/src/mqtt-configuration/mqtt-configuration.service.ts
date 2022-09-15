import { Injectable } from '@angular/core';
import { FetchClient, IdentityService, IExternalIdentity, IFetchResponse } from '@c8y/client';
import { LoginService } from '@c8y/ngx-components';
import { MQTTAuthentication } from '../shared/mqtt-configuration.model';

@Injectable({ providedIn: 'root' })
export class MQTTConfigurationService {
  private readonly PATH_CONNECT_ENDPOINT = 'connection';

  private readonly PATH_STATUS_ENDPOINT = 'status';
  private readonly PATH_OPERATION_ENDPOINT = 'operation';
  private readonly PATH_MONITORING_ENDPOINT = 'monitor-websocket';

  private readonly BASE_URL = 'service/generic-mqtt-agent';
  private mappingId: string;
  private agentId: string;
  
  constructor(private client: FetchClient,
    private identity: IdentityService,
    private loginService: LoginService) { }

  private getWebSocketUrl() {
    let url: string = this.client.getUrl();
    url = url.replace("http", "ws").replace("https", "wss");
    console.log("Strategy: ", this.loginService.getAuthStrategy(), url);

    if (true) {
      const options = this.client.getFetchOptions()
      const basicAuthHeader = options.headers.Authorization;
      console.log("FetchOptions: ", options, basicAuthHeader);
      const basicAuthtoken = basicAuthHeader.replace('Basic ', '');
      //url = `${url}${this.BASE_URL}/${this.PATH_MONITORING_ENDPOINT}?token=${basicAuthtoken}`;
      url = `${url}${this.BASE_URL}/${this.PATH_MONITORING_ENDPOINT}`;
    } else {
      let xsrf = this.getCookieValue('XSRF-TOKEN')
      url = `${url}${this.BASE_URL}/${this.PATH_MONITORING_ENDPOINT}?XSRF-TOKEN=${xsrf}`;
    }
    return url;
  }

  async initializeMQTTAgent(): Promise<string>{
    if (!this.agentId) {
      const identity: IExternalIdentity = {
        type: 'c8y_Serial',
        externalId: 'MQTT_AGENT'
      };

      this.agentId = null;
      const { data, res } = await this.identity.detail(identity);
      if (res.status < 300){
        this.agentId = data.managedObject.id.toString();
      }
      return this.agentId;
    }
  }

  private getCookieValue(name: string) {
    console.log("Cookie request:", document, name)
    const value = document.cookie.match('(^|;)\\s*' + name + '\\s*=\\s*([^;]+)');
    return value ? value.pop() : '';
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

  connectToMQTTBroker(): Promise<IFetchResponse> {
    return this.client.fetch(`${this.BASE_URL}/${this.PATH_OPERATION_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({ "operation": "CONNECT" }),
      method: 'POST',
    });
  }

  disconnectFromMQTTBroker(): Promise<IFetchResponse> {
    return this.client.fetch(`${this.BASE_URL}/${this.PATH_OPERATION_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({ "operation": "DISCONNECT" }),
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
