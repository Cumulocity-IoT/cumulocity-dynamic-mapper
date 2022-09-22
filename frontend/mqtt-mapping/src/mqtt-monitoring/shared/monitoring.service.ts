import { Injectable } from '@angular/core';
import { EventService, FetchClient, Realtime, IEvent, IResultList } from '@c8y/client';
import * as _ from 'lodash';
import { BehaviorSubject, Observable } from 'rxjs';
import { BrokerConfigurationService } from '../../mqtt-configuration/broker-configuration.service';
import { MappingStatus } from '../../shared/configuration.model';
import { MQTT_MONITORING_EVENT_TYPE } from '../../shared/helper';

@Injectable({ providedIn: 'root' })
export class MonitoringService {
  constructor(
    private client: FetchClient,
    private event: EventService,
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
    let dateFrom = this.addHoursToDate(new Date(), -1).toISOString();
    let dateTo = new Date().toISOString();
    let queryString = `type eq '${MQTT_MONITORING_EVENT_TYPE}' and dateFrom eq'${dateFrom} and dateTo eq '${dateTo}'`;
    const filter: object = {
      pageSize: 1,
      withTotalPages: true,
      type: MQTT_MONITORING_EVENT_TYPE,
      dateFrom: dateFrom,
      dateTo: dateTo,
      };
    let result : IResultList<IEvent> = await this.event.list(filter);
    if ( result?.data.length > 0) {
      let monitoring: MappingStatus[] = result?.data[0]['monitoring'];
      this.monitoringDetails.next(monitoring);
    }
    console.log("Found monitoring events", result.data);
    return this.realtime.subscribe(`/events/${this.agentId}`, this.updateMonitoring.bind(this));
  }

  private addHoursToDate(objDate, intHours) : Date{
    let numberOfMlSeconds = objDate.getTime();
    let addMlSeconds = (intHours * 60) * 60 * 1000;
    let newDateObj = new Date(numberOfMlSeconds + addMlSeconds);
    return newDateObj;
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