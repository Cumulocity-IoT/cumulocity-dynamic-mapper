import { Injectable } from '@angular/core';
import { FetchClient, IdentityService, IExternalIdentity, IFetchResponse } from '@c8y/client';
import { LoginService } from '@c8y/ngx-components';
import { EMPTY, Observable, Observer } from 'rxjs';
import { AnonymousSubject, Subject } from 'rxjs/internal/Subject';
import { catchError, map, switchAll, tap } from 'rxjs/operators';
import { MQTTAuthentication, StatusMessage } from '../mqtt-configuration.model';
import { Client, Message } from '@stomp/stompjs';

@Injectable({ providedIn: 'root' })
export class MQTTConfigurationService {
  private readonly PATH_CONNECT_ENDPOINT = 'connection';

  private readonly PATH_STATUS_ENDPOINT = 'status';
  private readonly PATH_OPERATION_ENDPOINT = 'operation';
  private readonly PATH_MONITORING_ENDPOINT = 'monitor-websocket';

  private readonly BASE_URL = 'service/generic-mqtt-agent';

  private isMQTTAgentCreated = false;
  private stompClient: Client;

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

  async initializeWebSocket(): Promise<void> {
    this.stompClient = new Client({
      brokerURL: this.getWebSocketUrl(),
      connectHeaders: {
       login: 't306817378/christof.strack@softwareag.com',
       passcode: '#Manage250!DFC',
     },
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
    });
    this.stompClient.brokerURL = this.getWebSocketUrl();
    this.stompClient.onConnect = function (frame) {
      const subscription = this.stompClient.subscribe('/topic/monitor', callback);
      console.log("Successfully connected!");
      // Do something, all subscribes must be done is this callback
      // This is needed because this will be executed after a (re)connect
    };
    
    this.stompClient.onStompError = function (frame) {
      // Will be invoked in case of error encountered at Broker
      // Bad login/passcode typically will cause an error
      // Complaint brokers will set `message` header with a brief message. Body may contain details.
      // Compliant brokers will terminate the connection after any error
      console.log('Broker reported error: ' + frame.headers['message']);
      console.log('Additional details: ' + frame.body);
    };

    this.stompClient.activate();

    let callback = function (message) {
      // called when the client receives a STOMP message from the server
      if (message.body) {
        console.log('got message with body ' + message.body);
      } else {
        console.log('got empty message');
      }
    };

    const subscription = this.stompClient.subscribe('/topic/monitor', callback);

  }

  async initializeMQTTAgent(): Promise<string> {
    const identity: IExternalIdentity = {
      type: 'c8y_Serial',
      externalId: 'MQTT_AGENT'
    };

    try {
      const { data, res } = await this.identity.detail(identity);
      console.log("Configuration result code: {}", res.status);
      this.isMQTTAgentCreated = true;
      let mo = data.managedObject.id.toString();
      return mo;
    } catch (error) {
      console.error("Configuration result code: {}", error);
      this.isMQTTAgentCreated = false;
    }
  }

  private getCookieValue(name: string) {
    console.log("Cookie request:", document, name)
    const value = document.cookie.match('(^|;)\\s*' + name + '\\s*=\\s*([^;]+)');
    return value ? value.pop() : '';
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
