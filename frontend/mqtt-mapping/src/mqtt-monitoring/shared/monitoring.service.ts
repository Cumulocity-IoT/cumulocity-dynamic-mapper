import { Injectable } from '@angular/core';
import { FetchClient, Realtime } from '@c8y/client';
import * as _ from 'lodash';
import { BehaviorSubject, Observable } from 'rxjs';
import { BrokerConfigurationService } from '../../mqtt-configuration/broker-configuration.service';
import { MappingStatus } from '../../shared/configuration.model';
import { MQTT_MONITORING_EVENT_TYPE } from '../../shared/helper';

@Injectable({ providedIn: 'root' })
export class MonitoringService {
  constructor(
    private client: FetchClient,
    private configurationService: BrokerConfigurationService) {
    this.realtime = new Realtime(this.client);
  }
  private realtime: Realtime
  private agentId: string;
  private monitoringDetails = new BehaviorSubject<MappingStatus[]>([]);
  private _currentMonitoringDetails = this.monitoringDetails.asObservable();

  public getCurrentMonitoringDetails(): Observable<MappingStatus[]>{
    return this._currentMonitoringDetails;
  }

  async subscribeToMonitoringChannel(): Promise<object> {
    this.agentId = await this.configurationService.initializeMQTTAgent();
    console.log("Start subscription for monitoring:", this.agentId);
    return this.realtime.subscribe(`/events/${this.agentId}`, this.updateMonitoring.bind(this));
  }

  unsubscribeFromMonitoringChannel(subscription: object): object {
    return this.realtime.unsubscribe(subscription);
  }

  private updateMonitoring( p: object): void{
    let payload = p['data']['data'];
    console.log("New generig event:", payload);
    if ( payload.type == MQTT_MONITORING_EVENT_TYPE) {
      let monitoring: MappingStatus[] = payload['monitoring'];
      this.monitoringDetails.next(monitoring);
      console.log("New monitoring event", monitoring);
    }
  }
}