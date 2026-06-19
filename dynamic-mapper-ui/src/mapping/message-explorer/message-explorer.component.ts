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

import {
  AfterViewInit,
  Component,
  inject,
  OnDestroy,
  OnInit,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import {
  ActionControl,
  AlertService,
  BottomDrawerService,
  Column,
  ColumnDataType,
  CoreModule,
  CountdownIntervalComponent,
  Pagination
} from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { filter, takeUntil } from 'rxjs/operators';
import { BehaviorSubject } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { InventoryService, IdentityService } from '@c8y/client';
import { ConnectorConfigurationService } from '../../shared/service/connector-configuration.service';
import { PollingInterval } from '../../shared/connector-configuration/connector.model';
import { ExplorerMessage, MessageExplorerService, SessionExpiredError } from './message-explorer.service';

export type IndexedMessage = ExplorerMessage & { id: number; seqNo: number };
import {
  ExplorerSessionSnapshot,
  ExplorerStartResult,
  MessageExplorerDrawerComponent
} from './message-explorer-drawer.component';
import { MappingTypeDrawerComponent } from '../mapping-create/mapping-type-drawer.component';
import { SubscriptionChoiceDrawerComponent } from './subscription-choice-drawer.component';
import { ALERT_INFO_TIMEOUT, Direction } from '../../shared';
import {
  MessageExplorerDateRendererComponent,
  MessageExplorerPayloadRendererComponent
} from './message-explorer-payload.renderer.component';

@Component({
  selector: 'd11r-message-explorer',
  templateUrl: './message-explorer.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, CommonModule, ReactiveFormsModule]
})
export class MessageExplorerComponent implements OnInit, AfterViewInit, OnDestroy {

  // ---- state ----------------------------------------------------------------
  sessionId: string | null = null;
  connectorName: string = '';
  sessionConnectorIdentifier: string = '';
  sessionTopic: string = '';
  sessionDirection: 'INBOUND' | 'OUTBOUND' = 'INBOUND';
  sessionMaxMessages: number = 50;
  sessionTTLMinutes: number = 10;
  sessionDeviceType: string | null = null;
  sessionSourceId: string | null = null;
  sessionDeviceTypeFilter: string | null = null;
  messages: IndexedMessage[] = [];
  paused: boolean = false;
  private nextSeqNo = 1;

  private static readonly SESSION_STORAGE_KEY = 'd11r-explorer-session';

  columns: Column[] = [
    {
      name: 'seqNo',
      header: '#',
      path: 'seqNo',
      dataType: ColumnDataType.TextShort,
      sortable: false,
      filterable: false,
      gridTrackSize: '60px'
    },
    {
      name: 'receivedAt',
      header: 'Received at',
      path: 'receivedAt',
      dataType: ColumnDataType.TextShort,
      sortable: true,
      filterable: false,
      cellRendererComponent: MessageExplorerDateRendererComponent,
      gridTrackSize: '200px'
    },
    {
      name: 'clientId',
      header: 'Client ID',
      path: 'clientId',
      dataType: ColumnDataType.TextShort,
      sortable: false,
      filterable: false,
      gridTrackSize: '140px'
    },
    {
      name: 'topic',
      header: 'Topic',
      path: 'topic',
      dataType: ColumnDataType.TextShort,
      sortable: false,
      filterable: true,
      gridTrackSize: '220px'
    },
    {
      name: 'payload',
      header: 'Payload',
      path: 'payload',
      dataType: ColumnDataType.TextShort,
      sortable: false,
      filterable: false,
      cellRendererComponent: MessageExplorerPayloadRendererComponent
    }
  ];

  actionControls: ActionControl[] = [
    {
      text: 'Create mapping',
      type: 'CREATE_MAPPING',
      icon: 'plus-circle',
      callback: (item: object) => this.onCreateMappingFromMessage(item as IndexedMessage)
    }
  ];

  pagination: Pagination = {
    pageSize: 50,
    currentPage: 1
  };

  // ---- countdown / polling (mirrors connector-grid.component.ts) ------------
  toggleIntervalForm: FormGroup;
  nextTriggerCountdown$ = new BehaviorSubject<number>(0);
  intervals: PollingInterval[] = [];
  currentPollingInterval: number;
  private shouldRefreshAutomatic = true;

  @ViewChild(CountdownIntervalComponent)
  countdownIntervalComponent!: CountdownIntervalComponent;

  private readonly destroy$ = new Subject<void>();

  // ---- DI -------------------------------------------------------------------
  private readonly fb = inject(FormBuilder);
  private readonly alertService = inject(AlertService);
  private readonly explorerService = inject(MessageExplorerService);
  private readonly connectorConfigService = inject(ConnectorConfigurationService);
  private readonly bottomDrawerService = inject(BottomDrawerService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly inventoryService = inject(InventoryService);
  private readonly identityService = inject(IdentityService);

  constructor() {
    this.toggleIntervalForm = this.fb.group({
      intervalToggle: true,
      refreshInterval: this.connectorConfigService.getCurrentPollingIntervalValue()
    });
  }

  ngOnInit(): void {
    this.intervals = this.connectorConfigService.getAvailablePollingIntervals();
    this.currentPollingInterval = this.connectorConfigService.getCurrentPollingIntervalValue();

    this.toggleIntervalForm.get('refreshInterval')?.valueChanges
      .pipe(takeUntil(this.destroy$), filter(Boolean))
      .subscribe(value => {
        this.currentPollingInterval = value;
        this.connectorConfigService.setPollingInterval(value);
        setTimeout(() => {
          this.nextTriggerCountdown$.next(this.currentPollingInterval);
          this.resetCountdown();
        });
      });

    this.tryResumeSession();
  }

  ngAfterViewInit(): void {
    setTimeout(() => this.startCountdown());
  }

  private persistSession(): void {
    const state = {
      sessionId: this.sessionId,
      connectorName: this.connectorName,
      sessionTopic: this.sessionTopic,
      sessionDirection: this.sessionDirection,
      sessionDeviceType: this.sessionDeviceType,
      sessionSourceId: this.sessionSourceId
    };
    localStorage.setItem(MessageExplorerComponent.SESSION_STORAGE_KEY, JSON.stringify(state));
  }

  private clearPersistedSession(): void {
    localStorage.removeItem(MessageExplorerComponent.SESSION_STORAGE_KEY);
  }

  private async tryResumeSession(): Promise<void> {
    const raw = localStorage.getItem(MessageExplorerComponent.SESSION_STORAGE_KEY);
    if (!raw) return;
    let state: any;
    try {
      state = JSON.parse(raw);
    } catch {
      this.clearPersistedSession();
      return;
    }
    if (!state?.sessionId) {
      this.clearPersistedSession();
      return;
    }
    try {
      const msgs = await this.explorerService.getMessages(state.sessionId);
      this.sessionId = state.sessionId;
      this.connectorName = state.connectorName ?? '';
      this.sessionTopic = state.sessionTopic ?? '';
      this.sessionDirection = state.sessionDirection ?? 'INBOUND';
      this.sessionDeviceType = state.sessionDeviceType ?? null;
      this.sessionSourceId = state.sessionSourceId ?? null;
      const indexed: IndexedMessage[] = msgs.map(m => {
        const seqNo = this.nextSeqNo++;
        return { ...m, id: seqNo, seqNo };
      });
      this.messages = indexed.slice().reverse();
      // Defer countdown start — @ViewChild is not available until ngAfterViewInit
      setTimeout(() => {
        this.nextTriggerCountdown$.next(this.currentPollingInterval);
        if (this.shouldRefreshAutomatic) {
          this.countdownIntervalComponent?.start();
        }
      });
      this.alertService.add({ text: 'Explorer session resumed.', type: 'info', timeout: ALERT_INFO_TIMEOUT });
    } catch (e) {
      this.clearPersistedSession();
      if (!(e instanceof SessionExpiredError)) {
        console.warn('Explorer session resume failed:', e);
      }
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    // Do NOT stop the backend session here — navigating away and back should resume the session.
    // The backend TTL expires idle sessions automatically. Explicit stop is handled by onStopSession().
  }

  // ---- polling --------------------------------------------------------------

  startCountdown(): void {
    this.nextTriggerCountdown$.next(this.currentPollingInterval);
    if (this.shouldRefreshAutomatic && this.sessionId) {
      this.countdownIntervalComponent?.start();
    }
  }

  resetCountdown(): void {
    this.countdownIntervalComponent?.reset();
  }

  async onCountdownEnded(): Promise<void> {
    this.resetCountdown();
    if (!this.sessionId) return;
    try {
      const msgs = await this.explorerService.getMessages(this.sessionId);
      // Identify which messages are new (not yet indexed) and assign consecutive seqNos.
      // Existing messages keep their seqNo even when the backend drops old ones from the buffer.
      const indexed: IndexedMessage[] = msgs.map(m => {
        const key = `${m.receivedAt}|${m.topic}|${m.connectorIdentifier}`;
        const existing = this.messages.find(em => `${em.receivedAt}|${em.topic}|${em.connectorIdentifier}` === key);
        if (existing) return existing;
        const seqNo = this.nextSeqNo++;
        return { ...m, id: seqNo, seqNo };
      });
      this.messages = indexed.slice().reverse();
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        // Session expired or was evicted on the backend — reset UI cleanly
        this.sessionId = null;
        this.connectorName = '';
        this.sessionTopic = '';
        this.messages = [];
        this.paused = false;
        this.countdownIntervalComponent?.stop();
        this.clearPersistedSession();
        this.alertService.warning('Explorer session has expired. Start a new session.');
      } else {
        // Transient network error — leave session alive, try again next tick
        console.warn('Explorer poll error:', e);
      }
    }
  }

  trackUserClickOnIntervalToggle(event: Event): void {
    this.shouldRefreshAutomatic = (event.target as HTMLInputElement).checked;
    if (!this.shouldRefreshAutomatic) {
      this.countdownIntervalComponent?.stop();
    } else if (this.sessionId) {
      this.countdownIntervalComponent?.start();
    }
  }

  // ---- session actions ------------------------------------------------------

  async onOpenDrawer(): Promise<void> {
    const drawer = this.bottomDrawerService.openDrawer(MessageExplorerDrawerComponent, {
      initialState: { activeSessionId: this.sessionId }
    });
    const result: ExplorerStartResult | null = await drawer.instance.result;
    if (!result) return;

    // Stop previous session if any
    if (this.sessionId) {
      await this.explorerService.stopSession(this.sessionId).catch(() => {});
      this.sessionId = null;
      this.messages = [];
    }

    try {
      this.sessionId = await this.explorerService.startSession({
        connectorIdentifier: result.connectorIdentifier,
        topic: result.topic,
        maxMessages: result.maxMessages,
        sessionTTLMinutes: result.sessionTTLMinutes,
        direction: result.direction,
        sourceId: result.sourceId,
        deviceType: result.deviceTypeFilter
      });
      this.connectorName = result.connectorName;
      this.sessionConnectorIdentifier = result.connectorIdentifier;
      this.sessionTopic = result.topic;
      this.sessionDirection = result.direction;
      this.sessionMaxMessages = result.maxMessages;
      this.sessionTTLMinutes = result.sessionTTLMinutes;
      this.sessionDeviceType = result.deviceType ?? null;
      this.sessionSourceId = result.sourceId ?? null;
      this.sessionDeviceTypeFilter = result.deviceTypeFilter ?? null;
      this.nextSeqNo = 1;
      this.paused = false;
      this.messages = [];
      this.persistSession();

      this.nextTriggerCountdown$.next(this.currentPollingInterval);
      if (this.shouldRefreshAutomatic) {
        this.countdownIntervalComponent?.start();
      }
      const deviceLabel = result.deviceName ? ` / device: "${result.deviceName}"` : '';
      this.alertService.success(`${result.direction === 'OUTBOUND' ? 'Outbound' : 'Inbound'}: exploring "${result.topic}" on "${result.connectorName}"${deviceLabel}`);
    } catch (e: any) {
      this.alertService.danger(`Failed to start explorer session: ${e.message}`);
    }
  }

  async onEditSession(): Promise<void> {
    if (!this.sessionId) return;
    const snapshot: ExplorerSessionSnapshot = {
      connectorIdentifier: this.sessionConnectorIdentifier,
      topic: this.sessionTopic,
      maxMessages: this.sessionMaxMessages,
      sessionTTLMinutes: this.sessionTTLMinutes,
      direction: this.sessionDirection,
      sourceId: this.sessionSourceId ?? undefined,
      deviceTypeFilter: this.sessionDeviceTypeFilter ?? undefined
    };
    const drawer = this.bottomDrawerService.openDrawer(MessageExplorerDrawerComponent, {
      initialState: { activeSessionId: this.sessionId, editSnapshot: snapshot }
    });
    const result: ExplorerStartResult | null = await drawer.instance.result;
    if (!result) return;

    // Stop the current session before starting the updated one
    await this.explorerService.stopSession(this.sessionId).catch(() => {});
    this.sessionId = null;
    this.messages = [];

    try {
      this.sessionId = await this.explorerService.startSession({
        connectorIdentifier: result.connectorIdentifier,
        topic: result.topic,
        maxMessages: result.maxMessages,
        sessionTTLMinutes: result.sessionTTLMinutes,
        direction: result.direction,
        sourceId: result.sourceId,
        deviceType: result.deviceTypeFilter
      });
      this.connectorName = result.connectorName;
      this.sessionConnectorIdentifier = result.connectorIdentifier;
      this.sessionTopic = result.topic;
      this.sessionDirection = result.direction;
      this.sessionMaxMessages = result.maxMessages;
      this.sessionTTLMinutes = result.sessionTTLMinutes;
      this.sessionDeviceType = result.deviceType ?? null;
      this.sessionSourceId = result.sourceId ?? null;
      this.sessionDeviceTypeFilter = result.deviceTypeFilter ?? null;
      this.nextSeqNo = 1;
      this.paused = false;
      this.persistSession();

      this.nextTriggerCountdown$.next(this.currentPollingInterval);
      if (this.shouldRefreshAutomatic) {
        this.countdownIntervalComponent?.start();
      }
      this.alertService.add({ text: 'Explorer session updated.', type: 'success', timeout: ALERT_INFO_TIMEOUT });
    } catch (e: any) {
      this.alertService.danger(`Failed to update explorer session: ${e.message}`);
    }
  }

  onTogglePause(): void {
    this.paused = !this.paused;
    if (this.paused) {
      this.countdownIntervalComponent?.stop();
    } else if (this.shouldRefreshAutomatic) {
      this.nextTriggerCountdown$.next(this.currentPollingInterval);
      this.countdownIntervalComponent?.start();
    }
  }

  async onStopSession(): Promise<void> {
    if (!this.sessionId) return;
    await this.explorerService.stopSession(this.sessionId).catch(() => {});
    this.sessionId = null;
    this.messages = [];
    this.connectorName = '';
    this.sessionConnectorIdentifier = '';
    this.sessionTopic = '';
    this.sessionDirection = 'INBOUND';
    this.sessionMaxMessages = 50;
    this.sessionTTLMinutes = 10;
    this.sessionDeviceType = null;
    this.sessionSourceId = null;
    this.sessionDeviceTypeFilter = null;
    this.paused = false;
    this.countdownIntervalComponent?.stop();
    this.clearPersistedSession();
    this.alertService.add({ text: 'Explorer session stopped.', type: 'info', timeout: ALERT_INFO_TIMEOUT });
  }

  async onClear(): Promise<void> {
    if (this.sessionId) {
      await this.explorerService.clearMessages(this.sessionId).catch(() => {});
    }
    this.messages = [];
  }

  // ---- create mapping from captured message ---------------------------------

  async onCreateMappingFromMessage(msg: ExplorerMessage): Promise<void> {
    const direction = msg.direction === 'OUTBOUND' ? Direction.OUTBOUND : Direction.INBOUND;

    // Step 1: pick mapping format / transformation type
    const typeDrawer = this.bottomDrawerService.openDrawer(MappingTypeDrawerComponent, {
      initialState: { direction }
    });
    let mappingResult: any;
    try {
      mappingResult = await typeDrawer.instance.result;
    } catch {
      return; // user cancelled
    }
    if (!mappingResult || typeof mappingResult === 'string') return;

    // Step 2 (OUTBOUND only): optionally create a subscription inline
    if (direction === Direction.OUTBOUND) {
      // Use device type already fetched when the session was started (from the selected device).
      // Fall back to fetching from msg.sourceId only if it wasn't available.
      let deviceType: string | null = this.sessionDeviceType;
      let deviceGroups: { id: string; name: string }[] = [];
      // Use msg.sourceId if available, otherwise fall back to the session's selected device
      const effectiveDeviceId = msg.sourceId ?? this.sessionSourceId;
      if (effectiveDeviceId) {
        try {
          const { data } = await this.inventoryService.detail(effectiveDeviceId, { withParents: true });
          if (deviceType === null) {
            deviceType = data['type'] ?? null;
          }
          const parentRefs: { id: string; self: string }[] = (data.assetParents?.references ?? [])
            .map((ref: any) => ({ id: String(ref.managedObject.id), self: ref.managedObject.self }));
          deviceGroups = (
            await Promise.all(
              parentRefs.map(async (ref) => {
                try {
                  const { data: groupData } = await this.inventoryService.detail(ref.id);
                  return { id: ref.id, name: groupData['name'] ?? ref.id };
                } catch {
                  return { id: ref.id, name: ref.id };
                }
              })
            )
          );
        } catch {
          // non-fatal — drawer will show no device context
        }
      }
      const subDrawer = this.bottomDrawerService.openDrawer(SubscriptionChoiceDrawerComponent, {
        initialState: { deviceType, deviceGroups }
      });
      const subResult = await subDrawer.instance.result;
      if (subResult === null) return; // user cancelled
      // subscription was either skipped or created inside the drawer — nothing more to do here
    }

    // Infer the C8Y API type from the outbound message topic (e.g. "EVENT/CREATE" → "EVENT")
    const targetAPI = direction === Direction.OUTBOUND ? this.inferTargetAPIFromTopic(msg.topic) : undefined;

    // Derive publishTopic / publishTopicSample from the API path (OUTBOUND only)
    let publishTopic: string | undefined;
    let publishTopicSample: string | undefined;
    if (targetAPI) {
      const apiPath = MessageExplorerComponent.API_C8Y_PATH[targetAPI];
      if (apiPath) {
        publishTopic = apiPath + '/#';
        // Try to resolve the c8y_Serial external ID of the source device
        // so the sample topic contains a real identifier instead of the literal "externalId".
        const effectiveSourceId = msg.sourceId ?? this.sessionSourceId;
        let externalIdLabel = 'externalId';
        if (effectiveSourceId) {
          try {
            const { data: extIds } = await this.identityService.list(effectiveSourceId);
            const serial = extIds.find((e: any) => e.type === 'c8y_Serial');
            if (serial?.externalId) {
              externalIdLabel = serial.externalId;
            }
          } catch {
            // non-fatal — keep the default label
          }
        }
        publishTopicSample = apiPath + '/' + externalIdLabel;
      }
    }
    // Navigate to the appropriate mapping grid; mapping.component.ts reads the state and opens the stepper
    const targetRoute = direction === Direction.INBOUND ? ['../inbound'] : ['../outbound'];
    this.router.navigate(targetRoute, {
      relativeTo: this.route,
      state: {
        fromExplorer: true,
        topic: msg.topic,
        payload: msg.payload,
        mappingType: mappingResult.mappingType,
        transformationType: mappingResult.transformationType,
        codeTemplate: mappingResult.codeTemplate,
        targetAPI,
        publishTopic,
        publishTopicSample
      }
    });
  }

  /**
   * Maps a Cumulocity API name to its REST path, mirroring the Java API enum.
   * Used to derive publishTopic / publishTopicSample for outbound mappings.
   */
  private static readonly API_C8Y_PATH: Record<string, string> = {
    ALARM: '/alarm/alarms',
    EVENT: '/event/events',
    MEASUREMENT: '/measurement/measurements',
    INVENTORY: '/inventory/managedObjects',
    OPERATION: '/devicecontrol/operations'
  };

  /**
   * Infer the Cumulocity targetAPI from an outbound topic.
   * Outbound topics have the form "<TYPE>/CREATE" or "<TYPE_WITH_CHILDREN>/CREATE",
   * where <TYPE> is one of EVENT, ALARM, MEASUREMENT, INVENTORY, OPERATION.
   */
  private inferTargetAPIFromTopic(topic: string): string | undefined {
    if (!topic) return undefined;
    const segment = topic.split('/')[0].toUpperCase();
    // EVENT_WITH_CHILDREN → EVENT
    for (const api of ['ALARM', 'EVENT', 'MEASUREMENT', 'INVENTORY', 'OPERATION']) {
      if (segment === api || segment.startsWith(api + '_')) {
        return api;
      }
    }
    return undefined;
  }
}
