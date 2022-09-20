import { Injectable } from '@angular/core';
import { FetchClient, IdentityService, IExternalIdentity, IFetchResponse } from '@c8y/client';
import { LoginService } from '@c8y/ngx-components';
import { AGENT_ID, BASE_URL, PATH_CONNECT_ENDPOINT, PATH_MONITORING_ENDPOINT, PATH_OPERATION_ENDPOINT, PATH_STATUS_ENDPOINT } from '../shared/mqtt-helper';
import { MQTTAuthentication } from '../shared/mqtt-configuration.model';

@Injectable({ providedIn: 'root' })
export class MQTTConfigurationService {

  private agentId: string = '';

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
      url = `${url}${BASE_URL}/${PATH_MONITORING_ENDPOINT}`;
    } else {
      let xsrf = this.getCookieValue('XSRF-TOKEN')
      url = `${url}${BASE_URL}/${PATH_MONITORING_ENDPOINT}?XSRF-TOKEN=${xsrf}`;
    }
    return url;
  }

  async initializeMQTTAgent(): Promise<string> {
    if (this.agentId == '') {
      const identity: IExternalIdentity = {
        type: 'c8y_Serial',
        externalId: AGENT_ID
      };

      this.agentId = null;
      const { data, res } = await this.identity.detail(identity);
      if (res.status < 300) {
        this.agentId = data.managedObject.id.toString();
        console.log("MQTTConfiguration: Found MQTTAgent", this.agentId );
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
    return this.client.fetch(`${BASE_URL}/${PATH_CONNECT_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify(mqttConfiguration),
      method: 'POST',
    });
  }

  connectToMQTTBroker(): Promise<IFetchResponse> {
    return this.client.fetch(`${BASE_URL}/${PATH_OPERATION_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({ "operation": "CONNECT" }),
      method: 'POST',
    });
  }

  disconnectFromMQTTBroker(): Promise<IFetchResponse> {
    return this.client.fetch(`${BASE_URL}/${PATH_OPERATION_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({ "operation": "DISCONNECT" }),
      method: 'POST',
    });
  }

  async getConnectionDetails(): Promise<MQTTAuthentication> {
    const response = await this.client.fetch(`${BASE_URL}/${PATH_CONNECT_ENDPOINT}`, {
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
    const response = await this.client.fetch(`${BASE_URL}/${PATH_STATUS_ENDPOINT}`, {
      method: 'GET',
    });
    const { status } = await response.json();
    return status;
  }

}
