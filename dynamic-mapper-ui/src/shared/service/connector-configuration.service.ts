/*
 * Copyright (c) 2025 Cumulocity GmbH
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
import { Injectable } from '@angular/core';
import { FetchClient, IFetchResponse } from '@c8y/client';
import {
  BehaviorSubject,
  Observable,
  Subject,
  combineLatest,
  from,
  merge,
  of,
  timer
} from 'rxjs';
import {
  map,
  shareReplay,
  switchMap,
  catchError,
  takeUntil,
  startWith,
  distinctUntilChanged,
  tap,
} from 'rxjs/operators';
import { BASE_URL, ConnectorConfiguration, ConnectorSpecification, ConnectorStatus, ConnectorStatusEvent, PATH_CONFIGURATION_CONNECTION_ENDPOINT, PATH_STATUS_CONNECTORS_ENDPOINT, PollingInterval } from '..';

interface ConnectorConfigurationState {
  configurations: ConnectorConfiguration[];
  specifications: ConnectorSpecification[];
  statusMap: { [identifier: string]: ConnectorStatusEvent };
  isLoading: boolean;
  error: string | null;
}

@Injectable({ providedIn: 'root' })
export class ConnectorConfigurationService {

  // Subscription management
  private readonly destroy$ = new Subject<void>();

  // State management
  private readonly state$ = new BehaviorSubject<ConnectorConfigurationState>({
    configurations: [],
    specifications: [],
    statusMap: {},
    isLoading: false,
    error: null
  });

  // Triggers
  private readonly refreshTrigger$ = new Subject<void>();

  // Polling configuration
  private readonly pollingInterval$ = new BehaviorSubject<number>(60000); // Default 60 seconds
  private readonly AVAILABLE_INTERVALS: PollingInterval[] = [
    { label: '5 seconds', value: 5000, seconds: 5 },
    { label: '15 seconds', value: 15000, seconds: 15 },
    { label: '30 seconds', value: 30000, seconds: 30 },
    { label: '1 minute', value: 60000, seconds: 60 }
  ];

  // Countdown active flag — controls whether the visual countdown in the grid is running
  private isCountdownActive = true;

  // Last successfully loaded status map — returned on poll error to preserve stale data
  // instead of resetting all connectors to UNKNOWN.
  private lastStatusMap: { [identifier: string]: ConnectorStatusEvent } = {};

  // Configuration
  private readonly CONFIG = {
    CACHE_DURATION: 5 * 60 * 1000, // 5 minutes
    RETRY_DELAY: 1000
  } as const;

  // Cache
  private specificationsCache: ConnectorSpecification[] | null = null;
  private cacheTimestamp: number = 0;

  private configurations$?: Observable<ConnectorConfiguration[]>;
  private configurationsWithStatus$?: Observable<ConnectorConfiguration[]>;
  private specifications$?: Observable<ConnectorSpecification[]>;

  constructor(
    private readonly client: FetchClient
  ) {}

  cleanUp(): void {
    // destroy$ must be completed before all subjects so takeUntil unsubscribes streams first
    this.destroy$.next();
    this.destroy$.complete();
    this.state$.complete();
    this.refreshTrigger$.complete();
    this.pollingInterval$.complete();
  }

  // Public API
  getConfigurations(): Observable<ConnectorConfiguration[]> {
    if (!this.configurations$) {
      this.configurations$ = this.createConfigurationsStream();
    }
    return this.configurations$;
  }

  getConfigurationsWithStatus(): Observable<ConnectorConfiguration[]> {
    if (!this.configurationsWithStatus$) {
      this.configurationsWithStatus$ = this.createConfigurationsWithStatusStream();
    }
    return this.configurationsWithStatus$;
  }

  getSpecifications(): Observable<ConnectorSpecification[]> {
    if (!this.specifications$) {
      this.specifications$ = this.createSpecificationsStream();
    }
    return this.specifications$;
  }

  // Polling configuration methods
  setPollingInterval(interval: number): void {
    this.pollingInterval$.next(interval);
  }

  getAvailablePollingIntervals(): PollingInterval[] {
    return [...this.AVAILABLE_INTERVALS];
  }

  getCurrentPollingIntervalValue(): number {
    return this.pollingInterval$.value;
  }

  getCurrentPollingInterval(): PollingInterval {
    return this.AVAILABLE_INTERVALS.find(item => item.value === this.pollingInterval$.value);
  }

  getCurrentPollingIntervalLabel(): string {
    const current = this.pollingInterval$.value;
    const found = this.AVAILABLE_INTERVALS.find(interval => interval.value === current);
    return found ? found.label : `${current / 1000} seconds`;
  }

  // Fix 3: only emit on the refresh trigger — do NOT reset the polling timer by
  // re-emitting to pollingInterval$, which would restart the auto-poll countdown.
  refreshConfigurations(): void {
    this.refreshTrigger$.next();
  }

  resetCache(): void {
    this.specificationsCache = null;
    this.cacheTimestamp = 0;
    this.updateState({
      specifications: [],
      statusMap: {}
    });
  }

  // Countdown control — called by the grid to pause/resume the visual countdown component.
  startCountdown(): void {
    this.isCountdownActive = true;
  }

  stopCountdown(): void {
    this.isCountdownActive = false;
  }

  isCountdownRunning(): boolean {
    return this.isCountdownActive;
  }

  toggleCountdown(): void {
    this.isCountdownActive = !this.isCountdownActive;
  }

  // CRUD Operations
  async createConfiguration(configuration: ConnectorConfiguration): Promise<IFetchResponse> {
    try {
      const response = await this.client.fetch(
        `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance`,
        {
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(configuration),
          method: 'POST'
        }
      );

      this.refreshConfigurations();
      return response;
    } catch (error) {
      console.error('Failed to create connector configuration:', error);
      throw error;
    }
  }

  async updateConfiguration(configuration: ConnectorConfiguration): Promise<IFetchResponse> {
    try {
      const response = await this.client.fetch(
        `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance/${configuration.identifier}`,
        {
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(configuration),
          method: 'PUT'
        }
      );

      this.refreshConfigurations();
      return response;
    } catch (error) {
      console.error('Failed to update connector configuration:', error);
      throw error;
    }
  }

  async deleteConfiguration(identifier: string): Promise<IFetchResponse> {
    if (!identifier?.trim()) {
      throw new Error('Identifier is required');
    }

    try {
      const response = await this.client.fetch(
        `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance/${identifier}`,
        {
          method: 'DELETE'
        }
      );

      this.refreshConfigurations();
      return response;
    } catch (error) {
      console.error('Failed to delete connector configuration:', error);
      throw error;
    }
  }

  async getConfiguration(identifier: string): Promise<ConnectorConfiguration> {
    if (!identifier?.trim()) {
      throw new Error('Identifier is required');
    }

    try {
      const response = await this.client.fetch(
        `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance/${identifier}`,
        {
          headers: { accept: 'application/json' },
          method: 'GET'
        }
      );

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      return await response.json();
    } catch (error) {
      console.error(`Failed to get connector configuration ${identifier}:`, error);
      throw error;
    }
  }

  // Private methods
  private createConfigurationsStream(): Observable<ConnectorConfiguration[]> {
    return this.refreshTrigger$.pipe(
      startWith(null), // Initial load
      switchMap(() => this.loadConfigurations()),
      catchError(error => {
        this.handleError('Failed to load configurations', error);
        return of([]);
      }),
      distinctUntilChanged(),
      shareReplay(1),
      takeUntil(this.destroy$)
    );
  }

  private createConfigurationsWithStatusStream(): Observable<ConnectorConfiguration[]> {
    return combineLatest([
      this.getConfigurations(),
      this.createStatusStream(),
      this.getSpecifications()
    ]).pipe(
      map(([configurations, statusMap, specifications]) =>
        this.enrichConfigurationsWithStatus(configurations, statusMap, specifications)
      ),
      shareReplay(1),
      takeUntil(this.destroy$)
    );
  }

  private createStatusStream(): Observable<{ [identifier: string]: ConnectorStatusEvent }> {
    const timerTrigger$ = this.pollingInterval$.pipe(
      switchMap(interval => timer(0, interval))
    );
    return merge(timerTrigger$, this.refreshTrigger$).pipe(
      switchMap(() => this.loadConnectorStatus()),
      catchError(error => {
        console.error('Failed to load connector status:', error);
        return of(this.lastStatusMap);
      }),
      shareReplay(1),
      takeUntil(this.destroy$)
    );
  }

  private createSpecificationsStream(): Observable<ConnectorSpecification[]> {
    return timer(0, this.CONFIG.CACHE_DURATION).pipe(
      switchMap(() => this.loadSpecifications()),
      catchError(error => {
        console.error('Failed to load specifications:', error);
        return of([]);
      }),
      takeUntil(this.destroy$)
    );
  }

  private loadConfigurations(): Observable<ConnectorConfiguration[]> {
    return from(this.fetchConfigurations()).pipe(
      catchError(error => {
        console.error('Failed to fetch configurations:', error);
        return of([]);
      })
    );
  }

  // Fix 6: cache each successful result; on error return stale data so connectors
  // keep their last known status instead of all flipping to UNKNOWN.
  private loadConnectorStatus(): Observable<{ [identifier: string]: ConnectorStatusEvent }> {
    return from(this.fetchConnectorStatus()).pipe(
      tap(statusMap => { this.lastStatusMap = statusMap; }),
      catchError(error => {
        console.error('Failed to fetch connector status:', error);
        this.updateState({ error: 'Status poll failed — showing last known status' });
        return of(this.lastStatusMap);
      })
    );
  }

  private loadSpecifications(): Observable<ConnectorSpecification[]> {
    if (this.isSpecificationsCacheValid()) {
      return of(this.specificationsCache!);
    }

    return from(this.fetchSpecifications()).pipe(
      map(specs => {
        this.specificationsCache = specs;
        this.cacheTimestamp = Date.now();
        return specs;
      }),
      catchError(error => {
        console.error('Failed to fetch specifications:', error);
        return of([]);
      })
    );
  }

  private async fetchConfigurations(): Promise<ConnectorConfiguration[]> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance`,
      {
        headers: { accept: 'application/json' },
        method: 'GET'
      }
    );

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }

    return await response.json();
  }

  private async fetchConnectorStatus(): Promise<{ [identifier: string]: ConnectorStatusEvent }> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_STATUS_CONNECTORS_ENDPOINT}`,
      { method: 'GET' }
    );

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }

    return await response.json();
  }

  private async fetchSpecifications(): Promise<ConnectorSpecification[]> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/specifications`,
      {
        headers: { accept: 'application/json' },
        method: 'GET'
      }
    );

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }

    return await response.json();
  }

  private enrichConfigurationsWithStatus(
    configurations: ConnectorConfiguration[],
    statusMap: { [identifier: string]: ConnectorStatusEvent },
    specifications: ConnectorSpecification[]
  ): ConnectorConfiguration[] {
    return configurations.map(config => ({
      ...config,
      status: statusMap[config.identifier]?.status || ConnectorStatus.UNKNOWN,
      supportedDirections: specifications.find(
        spec => spec.connectorType === config.connectorType
      )?.supportedDirections || []
    }));
  }

  private isSpecificationsCacheValid(): boolean {
    return this.specificationsCache !== null &&
      (Date.now() - this.cacheTimestamp) < this.CONFIG.CACHE_DURATION;
  }

  private updateState(partialState: Partial<ConnectorConfigurationState>): void {
    const currentState = this.state$.value;
    this.state$.next({ ...currentState, ...partialState });
  }

  private handleError(message: string, error: unknown): void {
    console.error(message, error);

    const errorMessage = error instanceof Error
      ? error.message
      : 'Unknown error occurred';

    this.updateState({
      error: `${message}: ${errorMessage}`,
      isLoading: false
    });
  }
}
