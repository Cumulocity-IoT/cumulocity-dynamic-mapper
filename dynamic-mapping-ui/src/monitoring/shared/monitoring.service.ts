/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */
import { Injectable } from "@angular/core";
import { FetchClient, InventoryService, Realtime } from "@c8y/client";
import { BehaviorSubject, Observable } from "rxjs";
import { MAPPING_STATUS_FRAGMENT, MappingStatus } from "../../shared";
import { BrokerConfigurationService } from "../../configuration";

@Injectable({ providedIn: "root" })
export class MonitoringService {
  constructor(
    private client: FetchClient,
    private inventory: InventoryService,
    private brokerConfigurationService: BrokerConfigurationService
  ) {
    this.realtime = new Realtime(this.client);
  }
  private realtime: Realtime;
  private agentId: string;
  private mappingStatus = new BehaviorSubject<MappingStatus[]>([]);
  private _currentMappingStatus = this.mappingStatus.asObservable();

  public getCurrentMappingStatus(): Observable<MappingStatus[]> {
    return this._currentMappingStatus;
  }

  async subscribeMonitoringChannel(): Promise<object> {
    this.agentId =
      await this.brokerConfigurationService.initializeBrokerAgent();
    console.log("Start subscription for monitoring:", this.agentId);

    let { data, res } = await this.inventory.detail(this.agentId);
    let monitoring: MappingStatus[] = data[MAPPING_STATUS_FRAGMENT];
    this.mappingStatus.next(monitoring);
    return this.realtime.subscribe(
      `/managedobjects/${this.agentId}`,
      this.updateStatus.bind(this)
    );
  }

  unsubscribeFromMonitoringChannel(subscription: object) {
    this.realtime.unsubscribe(subscription);
  }

  private updateStatus(p: object): void {
    let payload = p["data"]["data"];
    let monitoring: MappingStatus[] = payload[MAPPING_STATUS_FRAGMENT];
    this.mappingStatus.next(monitoring);
    console.log("New statusMonitoring event", monitoring);
  }
}
