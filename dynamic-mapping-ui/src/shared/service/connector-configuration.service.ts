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
import { inject, Injectable, OnDestroy } from '@angular/core';
import { FetchClient, IFetchResponse } from '@c8y/client';
import {
  BehaviorSubject,
  Observable,
  Subject,
  Subscription,
  combineLatest,
  from,
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
  takeWhile,
} from 'rxjs/operators';
import { BASE_URL, ConnectorConfiguration, ConnectorSpecification, ConnectorStatus, ConnectorStatusEvent, PATH_CONFIGURATION_CONNECTION_ENDPOINT, PATH_STATUS_CONNECTORS_ENDPOINT, PollingInterval } from '..';
import { EventRealtimeService, RealtimeSubjectService } from '@c8y/ngx-components';

interface ConnectorConfigurationState {
  configurations: ConnectorConfiguration[];
  specifications: ConnectorSpecification[];
  statusMap: { [identifier: string]: ConnectorStatusEvent };
  isLoading: boolean;
  error: string | null;
}
@Injectable({ providedIn: 'root' })
export class ConnectorConfigurationService implements OnDestroy {

  // Subscription management
  private readonly destroy$ = new Subject<void>();

  eventRealtimeService = new EventRealtimeService(
    inject(RealtimeSubjectService)
  );

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

  private countdownSubscription: Subscription | null = null;
  private isCountdownActive = true;
  private lastTriggerTime = 0;
  private currentInterval = 0;

  // Countdown state
  private readonly nextTriggerCountdown$ = new BehaviorSubject<number>(0);

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
    private client: FetchClient,
  ) { }

  ngOnDestroy(): void {
    console.log('ConnectorConfigurationService destroyed');
    this.destroy$.next();
    this.destroy$.complete();
    this.state$.complete();
    this.refreshTrigger$.complete();
    this.pollingInterval$.complete();
    this.nextTriggerCountdown$.complete();
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

  // Countdown API
  getNextTriggerCountdown(): Observable<number> {
    return this.nextTriggerCountdown$.asObservable();
  }

  // Polling configuration methods
  setPollingInterval(interval: number): void {
    // console.log(`Setting polling interval to ${interval} ms`);
    this.pollingInterval$.next(interval);
  }

  getAvailablePollingIntervals(): PollingInterval[] {
    return [...this.AVAILABLE_INTERVALS];
  }

  getCurrentPollingIntervalValue(): number {
    return this.pollingInterval$.value;
  }

  getCurrentPollingInterval(): PollingInterval {
    return this.AVAILABLE_INTERVALS.find(item => item.value == this.pollingInterval$.value);
  }

  getCurrentPollingIntervalLabel(): string {
    const current = this.pollingInterval$.value;
    const found = this.AVAILABLE_INTERVALS.find(interval => interval.value === current);
    return found ? found.label : `${current / 1000} seconds`;
  }

  refreshConfigurations(): void {
    this.refreshTrigger$.next();
    this.setPollingInterval(this.getCurrentPollingIntervalValue());
  }

  resetCache(): void {
    this.specificationsCache = null;
    this.cacheTimestamp = 0;
    this.updateState({
      specifications: [],
      statusMap: {}
    });
  }

  // Add these public methods to control countdown
  startCountdown(): void {
    if (this.isCountdownActive) {
      console.log('⏸️ Countdown already active');
      return;
    }

    this.isCountdownActive = true;
    console.log('▶️ Starting countdown');

    // Use the current interval and last trigger time to resume countdown
    this.createCountdownSubscription();
  }

  stopCountdown(): void {
    if (!this.isCountdownActive) {
      console.log('⏸️ Countdown already stopped');
      return;
    }

    this.isCountdownActive = false;
    console.log('⏹️ Stopping countdown');

    if (this.countdownSubscription) {
      this.countdownSubscription.unsubscribe();
      this.countdownSubscription = null;
    }

    // Optionally reset the countdown display
    this.nextTriggerCountdown$.next(0);
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

      // Refresh configurations after successful creation
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
    return this.pollingInterval$.pipe(
      switchMap(interval => {
        // console.log(`🔄 Creating status stream with ${interval}ms interval`);
        this.currentInterval = interval;

        return timer(0, interval).pipe(
          tap(() => {
            this.lastTriggerTime = Date.now();
            console.log(`⏰ Timer triggered - loading connector status`);

            // Only start countdown if it should be active
            if (this.isCountdownActive) {
              this.restartCountdown();
            }
          })
        );
      }),
      switchMap(() => this.loadConnectorStatus()),
      catchError(error => {
        console.error('Failed to load connector status:', error);
        return of({});
      }),
      shareReplay(1),
      takeUntil(this.destroy$)
    );
  }

  private restartCountdown(): void {
    // Clean up existing countdown
    if (this.countdownSubscription) {
      this.countdownSubscription.unsubscribe();
    }

    // Create new countdown subscription
    this.createCountdownSubscription();
  }

  private createCountdownSubscription(): void {
    if (!this.isCountdownActive || !this.currentInterval) {
      return;
    }

    this.countdownSubscription = timer(0, 1000).pipe(
      map(() => {
        const elapsed = Date.now() - this.lastTriggerTime;
        const remaining = Math.max(0, Math.ceil((this.currentInterval - elapsed) / 1000));
        return remaining;
      }),
      tap(remaining => {
        this.nextTriggerCountdown$.next(remaining * 1000); // in ms
        // console.log(`⏰ Next trigger in: ${remaining} seconds`);
      }),
      takeWhile(remaining => remaining > 0, true), // Include the final 0
      takeUntil(this.destroy$)
    ).subscribe({
      complete: () => {
        // Countdown completed naturally
        console.log('⏰ Countdown cycle completed');
      }
    });
  }

  // Optional: Method to check countdown status
  isCountdownRunning(): boolean {
    return this.isCountdownActive;
  }

  // Optional: Method to toggle countdown
  toggleCountdown(): void {
    if (this.isCountdownActive) {
      this.stopCountdown();
    } else {
      this.startCountdown();
    }
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

  private loadConnectorStatus(): Observable<{ [identifier: string]: ConnectorStatusEvent }> {
    return from(this.fetchConnectorStatus()).pipe(
      catchError(error => {
        console.error('Failed to fetch connector status:', error);
        return of({});
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