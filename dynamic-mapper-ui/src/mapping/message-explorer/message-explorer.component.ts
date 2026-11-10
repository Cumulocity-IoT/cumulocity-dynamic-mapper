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
import { AfterViewInit, Component, inject, OnDestroy, OnInit, ViewChild, ViewEncapsulation, Injector, ChangeDetectionStrategy, signal } from '@angular/core';
import { afterNextRenderInZone } from '../../shared/misc/after-render';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import {
  ActionControl,
  BulkActionControl,
  AlertService,
  BottomDrawerService,
  Column,
  CoreModule,
  CountdownIntervalComponent,
  Pagination
} from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { filter, takeUntil } from 'rxjs/operators';
import { BehaviorSubject } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { ExplorerMappingContextService } from './explorer-mapping-context.service';
import { EXPLORER_COLUMNS, OPTIONAL_EXPLORER_COLUMNS } from './message-explorer.columns';
import { clearStoredExplorerSession, readStoredExplorerSession, writeStoredExplorerSession } from './explorer-session-store';
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
import { ExplorerMappingHandoffService, ExplorerSampleMessage } from '../core/explorer-mapping-handoff.service';
import { ALERT_INFO_TIMEOUT, Direction, MappingType } from '../../shared';

import { provideGridPersistence } from '../../shared/misc/grid-persistence';

@Component({
  selector: 'd11r-message-explorer',
  templateUrl: './message-explorer.component.html',
  styleUrls: ['../../shared/mapping/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  providers: [...provideGridPersistence('message-explorer')],
  imports: [CoreModule, CommonModule, ReactiveFormsModule]
})
export class MessageExplorerComponent implements OnInit, AfterViewInit, OnDestroy {
  private readonly injector = inject(Injector);

  // ---- state ----------------------------------------------------------------
  private readonly sessionIdState = signal<string | null>(null);
  get sessionId(): string | null { return this.sessionIdState(); }
  set sessionId(value: string | null) { this.sessionIdState.set(value); }
  private readonly connectorNameState = signal<string>('');
  get connectorName(): string { return this.connectorNameState(); }
  set connectorName(value: string) { this.connectorNameState.set(value); }
  sessionConnectorIdentifier: string = '';
  private readonly sessionTopicState = signal<string>('');
  get sessionTopic(): string { return this.sessionTopicState(); }
  set sessionTopic(value: string) { this.sessionTopicState.set(value); }
  private readonly sessionDirectionState = signal<'INBOUND' | 'OUTBOUND'>('INBOUND');
  get sessionDirection(): 'INBOUND' | 'OUTBOUND' { return this.sessionDirectionState(); }
  set sessionDirection(value: 'INBOUND' | 'OUTBOUND') { this.sessionDirectionState.set(value); }
  sessionMaxMessages: number = 50;
  sessionTTLMinutes: number = 10;
  sessionDeviceType: string | null = null;
  private readonly sessionSourceIdState = signal<string | null>(null);
  get sessionSourceId(): string | null { return this.sessionSourceIdState(); }
  set sessionSourceId(value: string | null) { this.sessionSourceIdState.set(value); }
  private readonly sessionDeviceNameState = signal<string | null>(null);
  get sessionDeviceName(): string | null { return this.sessionDeviceNameState(); }
  set sessionDeviceName(value: string | null) { this.sessionDeviceNameState.set(value); }
  private readonly sessionDeviceTypeFilterState = signal<string | null>(null);
  get sessionDeviceTypeFilter(): string | null { return this.sessionDeviceTypeFilterState(); }
  set sessionDeviceTypeFilter(value: string | null) { this.sessionDeviceTypeFilterState.set(value); }
  private readonly sessionSubscriptionWarningState = signal<string | null>(null);
  get sessionSubscriptionWarning(): string | null { return this.sessionSubscriptionWarningState(); }
  set sessionSubscriptionWarning(value: string | null) { this.sessionSubscriptionWarningState.set(value); }
  private readonly messagesState = signal<IndexedMessage[]>([]);
  get messages(): IndexedMessage[] { return this.messagesState(); }
  set messages(value: IndexedMessage[]) { this.messagesState.set(value); }
  private readonly pausedState = signal<boolean>(false);
  get paused(): boolean { return this.pausedState(); }
  set paused(value: boolean) { this.pausedState.set(value); }
  private nextSeqNo = 1;

  private readonly allColumns: Column[] = EXPLORER_COLUMNS;

  private readonly columnsState = signal<Column[]>(this.allColumns.filter(
    c => !OPTIONAL_EXPLORER_COLUMNS.includes(c.name)
  ));
  get columns(): Column[] { return this.columnsState(); }
  set columns(value: Column[]) { this.columnsState.set(value); }

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
  private readonly mappingContext = inject(ExplorerMappingContextService);
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
    writeStoredExplorerSession({
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
    });
  }

  private async tryResumeSession(): Promise<void> {
    const state = readStoredExplorerSession();
    if (!state) return;
    try {
      const msgs = await this.explorerService.getMessages(state.sessionId);
      this.sessionId = state.sessionId;
      this.connectorName = state.connectorName;
      this.sessionConnectorIdentifier = state.sessionConnectorIdentifier;
      this.sessionTopic = state.sessionTopic;
      this.sessionDirection = state.sessionDirection;
      this.sessionMaxMessages = state.sessionMaxMessages;
      this.sessionTTLMinutes = state.sessionTTLMinutes;
      this.sessionDeviceType = state.sessionDeviceType;
      this.sessionSourceId = state.sessionSourceId;
      this.sessionDeviceName = state.sessionDeviceName;
      this.sessionDeviceTypeFilter = state.sessionDeviceTypeFilter;
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
      clearStoredExplorerSession();
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
      if (!OPTIONAL_EXPLORER_COLUMNS.includes(column.name)) {
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
        clearStoredExplorerSession();
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
    await this.replaceSession(result, 'start');
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
    await this.replaceSession(result, 'update');
  }

  /**
   * Stops the running session, if any, and starts the one the drawer asked for. `start` and `update` differ only in
   * the wording of the messages the user sees.
   */
  private async replaceSession(result: ExplorerStartResult, mode: 'start' | 'update'): Promise<void> {
    if (this.sessionId) {
      await this.explorerService.stopSession(this.sessionId).catch(() => {});
      this.sessionId = null;
    }
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
        const text =
          mode === 'start'
            ? gettext('Explorer session started, but subscribing to the topic failed: {{warning}}')
            : gettext('Explorer session updated, but subscribing to the topic failed: {{warning}}');
        this.alertService.warning(this.translateService.instant(text, { warning: this.sessionSubscriptionWarning }));
      } else if (mode === 'update') {
        this.alertService.add({ text: gettext('Explorer session updated.'), type: 'success', timeout: ALERT_INFO_TIMEOUT });
      }

      this.nextTriggerCountdown$.next(this.currentPollingInterval);
      if (this.shouldRefreshAutomatic) {
        this.countdownIntervalComponent?.start();
      }
    } catch (e: any) {
      const text =
        mode === 'start'
          ? gettext('Failed to start explorer session: {{error}}')
          : gettext('Failed to update explorer session: {{error}}');
      this.alertService.danger(this.translateService.instant(text, { error: e.message }));
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
    clearStoredExplorerSession();
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
    // Outbound mappings are built from one message: its type decides the filter, the subscription and the source API.
    if (msgs.some(m => m.direction === 'OUTBOUND')) return false;
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

    // Step 2 (OUTBOUND only): the source device's type and groups become one-click choices in the mapping's
    // Subscription step. Nothing is subscribed here; the step writes on save.
    const subscriptionSuggestions =
      direction === Direction.OUTBOUND
        ? await this.mappingContext.resolveSubscriptionSuggestions(msg.sourceId, this.sessionSourceId, this.sessionDeviceType)
        : undefined;

    // Extra samples only help AI generation of a Smart Function; any other choice uses the primary alone.
    const useExtras = extraMsgs.length > 0 && !!mappingResult.generateSmartFunctionWithAI;
    if (extraMsgs.length > 0 && !useExtras) {
      this.alertService.info(gettext('Only the first selected message is used, because the mapping is not generated with AI.'));
    }
    const additionalMessages: ExplorerSampleMessage[] | undefined = useExtras
      ? extraMsgs.map(m => ({ topic: m.topic, payload: m.payload, binary: m.binary, key: m.key, metadata: m.metadata }))
      : undefined;

    // Infer the C8Y API type from the outbound message topic (e.g. "EVENT/CREATE" -> "EVENT"), and derive the
    // publish topic and a sample topic from its API path (OUTBOUND only).
    const targetAPI = direction === Direction.OUTBOUND ? this.mappingContext.inferTargetAPIFromTopic(msg.topic) : undefined;
    const { publishTopic, publishTopicSample } = await this.mappingContext.resolvePublishTopics(
      targetAPI,
      msg.sourceId ?? this.sessionSourceId
    );
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
      publishTopicSample,
      subscriptionSuggestions
    });
    const targetRoute = direction === Direction.INBOUND ? ['../inbound'] : ['../outbound'];
    this.router.navigate(targetRoute, { relativeTo: this.route });
  }

  /**
   * Maps a Cumulocity API name to its REST path, mirroring the Java API enum.
   * Used to derive publishTopic / publishTopicSample for outbound mappings.
   */
  /**
   * Infer the Cumulocity targetAPI from an outbound topic.
   * Outbound topics have the form "<TYPE>/CREATE" or "<TYPE_WITH_CHILDREN>/CREATE",
   * where <TYPE> is one of EVENT, ALARM, MEASUREMENT, INVENTORY, OPERATION.
   */
}
