/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

import { gettext } from '@c8y/ngx-components/gettext';
import { TranslateService } from '@ngx-translate/core';
import { AfterViewInit, Component, inject, OnDestroy, OnInit, ViewChild, ViewEncapsulation, Injector } from '@angular/core';
import { afterNextRenderInZone } from '../../shared/misc/after-render';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import {
  ActionControl,
  BulkActionControl,
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
import { ExplorerMessage, isRawPayload, MAX_EXPLORER_SAMPLES, MessageExplorerService, samplePayloadKind, SessionExpiredError } from './message-explorer.service';

export type IndexedMessage = ExplorerMessage & { id: number; seqNo: number };
import {
  ExplorerSessionSnapshot,
  ExplorerStartResult,
  MessageExplorerDrawerComponent
} from './message-explorer-drawer.component';
import { MappingTypeDrawerComponent } from '../mapping-create/mapping-type-drawer.component';
import { SubscriptionChoiceDrawerComponent } from './subscription-choice-drawer.component';
import { ExplorerMappingHandoffService, ExplorerSampleMessage } from '../core/explorer-mapping-handoff.service';
import { ALERT_INFO_TIMEOUT, Direction, MappingType } from '../../shared';
import {
  MessageExplorerDateRendererComponent,
  MessageExplorerTruncateRendererComponent,
  MessageExplorerPayloadRendererComponent
} from './message-explorer-payload.renderer.component';

import { provideGridPersistence } from '../../shared/misc/grid-persistence';

@Component({
  selector: 'd11r-message-explorer',
  templateUrl: './message-explorer.component.html',
  styleUrls: ['../../shared/mapping/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  providers: [...provideGridPersistence('message-explorer')],
  imports: [CoreModule, CommonModule, ReactiveFormsModule]
})
export class MessageExplorerComponent implements OnInit, AfterViewInit, OnDestroy {
  private readonly injector = inject(Injector);

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
  sessionDeviceName: string | null = null;
  sessionDeviceTypeFilter: string | null = null;
  sessionSubscriptionWarning: string | null = null;
  messages: IndexedMessage[] = [];
  paused: boolean = false;
  private nextSeqNo = 1;

  private static readonly SESSION_STORAGE_KEY = 'd11r-explorer-session';

  // Columns only present for some broker protocols: clientId is set by the MQTT connectors,
  // key by Kafka (inbound only - the outbound record key is applied further downstream, after
  // the explorer has already captured the message). Rather than hardcoding a connector-type
  // list, they are shown only once a captured message actually carries a value - see
  // refreshColumns().
  private static readonly OPTIONAL_COLUMNS: readonly string[] = ['clientId', 'key'];

  private readonly allColumns: Column[] = [
    {
      name: 'seqNo',
      header: '#',
      path: 'seqNo',
      dataType: ColumnDataType.TextShort,
      sortable: false,
      filterable: false,
      gridTrackSize: '5%'
    },
    {
      name: 'receivedAt',
      header: gettext('Received at'),
      path: 'receivedAt',
      dataType: ColumnDataType.TextShort,
      sortable: true,
      filterable: false,
      cellRendererComponent: MessageExplorerDateRendererComponent,
      gridTrackSize: '12%'
    },
    {
      name: 'clientId',
      header: gettext('Client ID'),
      path: 'clientId',
      dataType: ColumnDataType.TextShort,
      sortable: false,
      filterable: false,
      cellRendererComponent: MessageExplorerTruncateRendererComponent,
      gridTrackSize: '12%'
    },
    {
      name: 'key',
      header: gettext('Key'),
      path: 'key',
      dataType: ColumnDataType.TextShort,
      sortable: false,
      filterable: false,
      cellRendererComponent: MessageExplorerTruncateRendererComponent,
      gridTrackSize: '14%'
    },
    {
      name: 'topic',
      header: gettext('Topic'),
      path: 'topic',
      dataType: ColumnDataType.TextShort,
      sortable: false,
      filterable: true,
      gridTrackSize: '12%'
    },
    {
      name: 'payload',
      header: gettext('Payload'),
      path: 'payload',
      dataType: ColumnDataType.TextShort,
      sortable: false,
      filterable: false,
      cellRendererComponent: MessageExplorerPayloadRendererComponent
    }
  ];

  columns: Column[] = this.allColumns.filter(
    c => !MessageExplorerComponent.OPTIONAL_COLUMNS.includes(c.name)
  );

  actionControls: ActionControl[] = [
    {
      text: gettext('Create mapping'),
      type: 'CREATE_MAPPING',
      icon: 'plus-circle',
      callback: (item: object) => this.onCreateMappingFromMessage(item as IndexedMessage)
    }
  ];

  /** Bulk action on the selected rows: one Smart Function generated by AI from several messages.
   *  Offered only for 2..MAX_EXPLORER_SAMPLES messages of the same payload type (JSON with JSON,
   *  binary with binary, ...), since one function has to read all of them. */
  bulkActionControls: BulkActionControl[] = [
    {
      text: gettext('Create mapping from selected messages'),
      type: 'CREATE_MAPPING_FROM_SELECTION',
      icon: 'plus-circle',
      showIf: (selectedItemIds: (string | number)[]) => this.canCombine(this.selectedMessages(selectedItemIds)),
      callback: (selectedItemIds: (string | number)[]) =>
        this.onCreateMappingFromMessages(this.selectedMessages(selectedItemIds))
    }
  ];

  readonly maxSamples = MAX_EXPLORER_SAMPLES;

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
  private readonly translateService = inject(TranslateService);
  private readonly explorerService = inject(MessageExplorerService);
  private readonly connectorConfigService = inject(ConnectorConfigurationService);
  private readonly bottomDrawerService = inject(BottomDrawerService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly inventoryService = inject(InventoryService);
  private readonly identityService = inject(IdentityService);
  private readonly explorerMappingHandoff = inject(ExplorerMappingHandoffService);

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
        // After the render that hands the new interval to the countdown component.
        afterNextRenderInZone(this.injector, () => {
          this.nextTriggerCountdown$.next(this.currentPollingInterval);
          this.resetCountdown();
        });
      });

    this.tryResumeSession();
  }

  ngAfterViewInit(): void {
    afterNextRenderInZone(this.injector, () => this.startCountdown());
  }

  private persistSession(): void {
    const state = {
      sessionId: this.sessionId,
      connectorName: this.connectorName,
      sessionConnectorIdentifier: this.sessionConnectorIdentifier,
      sessionTopic: this.sessionTopic,
      sessionDirection: this.sessionDirection,
      sessionMaxMessages: this.sessionMaxMessages,
      sessionTTLMinutes: this.sessionTTLMinutes,
      sessionDeviceType: this.sessionDeviceType,
      sessionSourceId: this.sessionSourceId,
      sessionDeviceName: this.sessionDeviceName,
      sessionDeviceTypeFilter: this.sessionDeviceTypeFilter
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
      this.sessionConnectorIdentifier = state.sessionConnectorIdentifier ?? '';
      this.sessionTopic = state.sessionTopic ?? '';
      this.sessionDirection = state.sessionDirection ?? 'INBOUND';
      this.sessionMaxMessages = state.sessionMaxMessages ?? 50;
      this.sessionTTLMinutes = state.sessionTTLMinutes ?? 10;
      this.sessionDeviceType = state.sessionDeviceType ?? null;
      this.sessionSourceId = state.sessionSourceId ?? null;
      this.sessionDeviceName = state.sessionDeviceName ?? null;
      this.sessionDeviceTypeFilter = state.sessionDeviceTypeFilter ?? null;
      const indexed: IndexedMessage[] = msgs.map(m => {
        const seqNo = this.nextSeqNo++;
        return { ...m, id: seqNo, seqNo };
      });
      this.setMessages(indexed.slice().reverse());
      // The countdown component (@ViewChild) exists only after the first render.
      afterNextRenderInZone(this.injector, () => {
        this.nextTriggerCountdown$.next(this.currentPollingInterval);
        if (this.shouldRefreshAutomatic) {
          this.countdownIntervalComponent?.start();
        }
      });
      // this.alertService.add({ text: 'Explorer session resumed.', type: 'info', timeout: ALERT_INFO_TIMEOUT });
    } catch (e) {
      this.clearPersistedSession();
      if (!(e instanceof SessionExpiredError)) {
        console.warn('Explorer session resume failed:', e);
      }
    }
  }

  /** Assigns the message buffer and re-evaluates which optional columns to show. */
  private setMessages(messages: IndexedMessage[]): void {
    this.messages = messages;
    this.refreshColumns();
  }

  /**
   * Hides the protocol-specific columns (see OPTIONAL_COLUMNS) while no captured message
   * carries a value for them, so e.g. an outbound session - or any connector that has no
   * such concept - does not show a permanently empty column.
   */
  private refreshColumns(): void {
    this.columns = this.allColumns.filter(column => {
      if (!MessageExplorerComponent.OPTIONAL_COLUMNS.includes(column.name)) {
        return true;
      }
      return this.messages.some(message => {
        const value = message[column.name as keyof IndexedMessage];
        return value !== null && value !== undefined && value !== '';
      });
    });
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
      this.setMessages(indexed.slice().reverse());
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        // Session expired or was evicted on the backend — reset UI cleanly
        this.sessionId = null;
        this.connectorName = '';
        this.sessionTopic = '';
        this.setMessages([]);
        this.paused = false;
        this.countdownIntervalComponent?.stop();
        this.clearPersistedSession();
        this.alertService.warning(gettext('Explorer session has expired. Start a new session.'));
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
      this.setMessages([]);
    }

    try {
      const started = await this.explorerService.startSession({
        connectorIdentifier: result.connectorIdentifier,
        topic: result.topic,
        maxMessages: result.maxMessages,
        sessionTTLMinutes: result.sessionTTLMinutes,
        direction: result.direction,
        sourceId: result.sourceId,
        deviceType: result.deviceTypeFilter
      });
      this.sessionId = started.sessionId;
      this.sessionSubscriptionWarning = started.subscriptionWarning ?? null;
      this.connectorName = result.connectorName;
      this.sessionConnectorIdentifier = result.connectorIdentifier;
      this.sessionTopic = result.topic;
      this.sessionDirection = result.direction;
      this.sessionMaxMessages = result.maxMessages;
      this.sessionTTLMinutes = result.sessionTTLMinutes;
      this.sessionDeviceType = result.deviceType ?? null;
      this.sessionSourceId = result.sourceId ?? null;
      this.sessionDeviceName = result.deviceName ?? null;
      this.sessionDeviceTypeFilter = result.deviceTypeFilter ?? null;
      this.nextSeqNo = 1;
      this.paused = false;
      this.setMessages([]);
      this.persistSession();

      if (this.sessionSubscriptionWarning) {
        this.alertService.warning(
          this.translateService.instant(gettext('Explorer session started, but subscribing to the topic failed: {{warning}}'), { warning: this.sessionSubscriptionWarning })
        );
      }

      this.nextTriggerCountdown$.next(this.currentPollingInterval);
      if (this.shouldRefreshAutomatic) {
        this.countdownIntervalComponent?.start();
      }
    } catch (e: any) {
      this.alertService.danger(
        this.translateService.instant(gettext('Failed to start explorer session: {{error}}'), { error: e.message })
      );
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
      deviceName: this.sessionDeviceName ?? undefined,
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
    this.setMessages([]);

    try {
      const started = await this.explorerService.startSession({
        connectorIdentifier: result.connectorIdentifier,
        topic: result.topic,
        maxMessages: result.maxMessages,
        sessionTTLMinutes: result.sessionTTLMinutes,
        direction: result.direction,
        sourceId: result.sourceId,
        deviceType: result.deviceTypeFilter
      });
      this.sessionId = started.sessionId;
      this.sessionSubscriptionWarning = started.subscriptionWarning ?? null;
      this.connectorName = result.connectorName;
      this.sessionConnectorIdentifier = result.connectorIdentifier;
      this.sessionTopic = result.topic;
      this.sessionDirection = result.direction;
      this.sessionMaxMessages = result.maxMessages;
      this.sessionTTLMinutes = result.sessionTTLMinutes;
      this.sessionDeviceType = result.deviceType ?? null;
      this.sessionSourceId = result.sourceId ?? null;
      this.sessionDeviceName = result.deviceName ?? null;
      this.sessionDeviceTypeFilter = result.deviceTypeFilter ?? null;
      this.nextSeqNo = 1;
      this.paused = false;
      this.persistSession();

      if (this.sessionSubscriptionWarning) {
        this.alertService.warning(
          this.translateService.instant(gettext('Explorer session updated, but subscribing to the topic failed: {{warning}}'), { warning: this.sessionSubscriptionWarning })
        );
      } else {
        this.alertService.add({ text: gettext('Explorer session updated.'), type: 'success', timeout: ALERT_INFO_TIMEOUT });
      }

      this.nextTriggerCountdown$.next(this.currentPollingInterval);
      if (this.shouldRefreshAutomatic) {
        this.countdownIntervalComponent?.start();
      }
    } catch (e: any) {
      this.alertService.danger(
        this.translateService.instant(gettext('Failed to update explorer session: {{error}}'), { error: e.message })
      );
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
    this.setMessages([]);
    this.connectorName = '';
    this.sessionConnectorIdentifier = '';
    this.sessionTopic = '';
    this.sessionDirection = 'INBOUND';
    this.sessionMaxMessages = 50;
    this.sessionTTLMinutes = 10;
    this.sessionDeviceType = null;
    this.sessionSourceId = null;
    this.sessionDeviceName = null;
    this.sessionDeviceTypeFilter = null;
    this.paused = false;
    this.countdownIntervalComponent?.stop();
    this.clearPersistedSession();
    this.alertService.add({ text: gettext('Explorer session stopped.'), type: 'info', timeout: ALERT_INFO_TIMEOUT });
  }

  async onClear(): Promise<void> {
    if (this.sessionId) {
      await this.explorerService.clearMessages(this.sessionId).catch(() => {});
    }
    this.setMessages([]);
  }

  // ---- create mapping from captured message ---------------------------------

  private selectedMessages(selectedItemIds: (string | number)[] | undefined): IndexedMessage[] {
    const ids = new Set((selectedItemIds ?? []).map(String));
    return this.messages.filter(m => ids.has(String(m.id)));
  }

  private canCombine(msgs: IndexedMessage[]): boolean {
    return msgs.length >= 2
      && msgs.length <= MAX_EXPLORER_SAMPLES
      && msgs.every(m => samplePayloadKind(m) === samplePayloadKind(msgs[0]))
      && msgs.every(m => m.direction === msgs[0].direction);
  }

  /** Several selected messages: the oldest becomes the primary sample, the rest are extra examples. */
  async onCreateMappingFromMessages(msgs: IndexedMessage[]): Promise<void> {
    if (!this.canCombine(msgs)) {
      this.alertService.warning(
        this.translateService.instant(
          gettext('Select between 2 and {{max}} messages of the same type, for example only JSON or only binary.'),
          { max: MAX_EXPLORER_SAMPLES }
        )
      );
      return;
    }
    const ordered = [...msgs].sort((a, b) => a.receivedAt - b.receivedAt);
    await this.onCreateMappingFromMessage(ordered[0], ordered.slice(1));
  }

  async onCreateMappingFromMessage(msg: ExplorerMessage, extraMsgs: ExplorerMessage[] = []): Promise<void> {
    const direction = msg.direction === 'OUTBOUND' ? Direction.OUTBOUND : Direction.INBOUND;

    // Step 1: pick mapping format / transformation type
    const typeDrawer = this.bottomDrawerService.openDrawer(MappingTypeDrawerComponent, {
      // Binary, XML and plain-text payloads cannot be parsed as JSON, so preselect Any Payload.
      initialState: { direction, preferredMappingType: isRawPayload(msg) ? MappingType.ANY_PAYLOAD : undefined }
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
      // Use msg.sourceId if available, otherwise fall back to the session's selected device
      const effectiveDeviceId = msg.sourceId ?? this.sessionSourceId;
      // Reuse the type already fetched when the session started only if the message actually
      // originated from that exact source. When the session source is a device group, messages
      // arrive from its member devices — sessionDeviceType would then be the group's own type
      // (e.g. "c8y_DeviceGroup"), not the originating device's, so it must be refetched below.
      let deviceType: string | null =
        effectiveDeviceId && effectiveDeviceId === this.sessionSourceId ? this.sessionDeviceType : null;
      let deviceGroups: { id: string; name: string }[] = [];
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
        initialState: { deviceId: effectiveDeviceId ?? null, deviceType, deviceGroups }
      });
      const subResult = await subDrawer.instance.result;
      if (subResult === null) return; // user cancelled
      // subscription was either skipped or created inside the drawer — nothing more to do here
    }

    // Extra samples only help AI generation of a Smart Function; any other choice uses the primary alone.
    const useExtras = extraMsgs.length > 0 && !!mappingResult.generateSmartFunctionWithAI;
    if (extraMsgs.length > 0 && !useExtras) {
      this.alertService.info(gettext('Only the first selected message is used, because the mapping is not generated with AI.'));
    }
    const additionalMessages: ExplorerSampleMessage[] | undefined = useExtras
      ? extraMsgs.map(m => ({ topic: m.topic, payload: m.payload, binary: m.binary, key: m.key, metadata: m.metadata }))
      : undefined;

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
    // Hand the captured message + chosen mapping/transformation type off to the mapping grid,
    // which applies it once it loads (see ExplorerMappingHandoffService).
    this.explorerMappingHandoff.set({
      sessionTopic: this.sessionTopic,
      topic: msg.topic,
      payload: msg.payload,
      binary: msg.binary,
      payloadType: msg.payloadType,
      binaryFormat: msg.binaryFormat,
      payloadSize: msg.payloadSize,
      clientId: msg.clientId,
      connectorType: msg.connectorType,
      key: msg.key,
      metadata: msg.metadata,
      additionalMessages,
      mappingType: mappingResult.mappingType,
      transformationType: mappingResult.transformationType,
      codeTemplate: mappingResult.codeTemplate,
      generateSmartFunctionWithAI: mappingResult.generateSmartFunctionWithAI,
      targetAPI,
      publishTopic,
      publishTopicSample
    });
    const targetRoute = direction === Direction.INBOUND ? ['../inbound'] : ['../outbound'];
    this.router.navigate(targetRoute, { relativeTo: this.route });
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
