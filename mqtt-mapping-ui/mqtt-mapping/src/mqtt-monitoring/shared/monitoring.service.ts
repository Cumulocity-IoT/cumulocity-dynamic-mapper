import { Injectable } from '@angular/core';
import { FetchClient, InventoryService, Realtime } from '@c8y/client';
import { BehaviorSubject, Observable } from 'rxjs';
import { BrokerConfigurationService } from '../../mqtt-configuration/broker-configuration.service';
import { MappingStatus } from '../../shared/mapping.model';

@Injectable({ providedIn: 'root' })
export class MonitoringService {
  constructor(
    private client: FetchClient,
    private inventory: InventoryService,
    private configurationService: BrokerConfigurationService) {
    this.realtime = new Realtime(this.client);
  }
  private realtime: Realtime
  private agentId: string;
  private mappingStatus = new BehaviorSubject<MappingStatus[]>([]);
  private _currentMappingStatus = this.mappingStatus.asObservable();

  public getCurrentMappingStatus(): Observable<MappingStatus[]> {
    return this._currentMappingStatus;
  }

  async subscribeMonitoringChannel(): Promise<object> {
    this.agentId = await this.configurationService.initializeMQTTAgent();
    console.log("Start subscription for monitoring:", this.agentId);

    let { data, res } = await this.inventory.detail(this.agentId);
    let monitoring: MappingStatus[] = data['mapping_status'];
    this.mappingStatus.next(monitoring);
    return this.realtime.subscribe(`/managedobjects/${this.agentId}`, this.updateStatus.bind(this));
  }

  unsubscribeFromMonitoringChannel(subscription: object): object {
    return this.realtime.unsubscribe(subscription);
  }

  private updateStatus(p: object): void {
    let payload = p['data']['data'];
    let monitoring: MappingStatus[] = payload['mapping_status'];
    this.mappingStatus.next(monitoring);
    console.log("New statusMonitoring event", monitoring);
  }
}