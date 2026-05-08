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
  AlertService,
  BottomDrawerService,
  CoreModule,
  CountdownIntervalComponent
} from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { filter, takeUntil } from 'rxjs/operators';
import { BehaviorSubject } from 'rxjs';
import { ConnectorConfigurationService } from '../../shared/service/connector-configuration.service';
import { PollingInterval } from '../../shared/connector-configuration/connector.model';
import { ExplorerMessage, MessageExplorerService, SessionExpiredError } from './message-explorer.service';
import {
  ExplorerStartResult,
  MessageExplorerDrawerComponent
} from './message-explorer-drawer.component';
import { JsonEditorComponent } from '../../shared/component/json-editor/jsoneditor.component';

@Component({
  selector: 'd11r-message-explorer',
  templateUrl: './message-explorer.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, CommonModule, ReactiveFormsModule, JsonEditorComponent]
})
export class MessageExplorerComponent implements OnInit, AfterViewInit, OnDestroy {

  // ---- state ----------------------------------------------------------------
  sessionId: string | null = null;
  connectorName: string = '';
  sessionTopic: string = '';
  sessionDirection: 'INBOUND' | 'OUTBOUND' = 'INBOUND';
  messages: ExplorerMessage[] = [];
  paused: boolean = false;
  expandedIndex: number | null = null;
  // Pre-parsed payload for the expanded row — avoids calling JSON.parse on every CD cycle
  expandedPayload: { isJson: boolean; parsed: any; raw: string } | null = null;

  // ---- countdown / polling (mirrors connector-grid.component.ts) ------------
  toggleIntervalForm: FormGroup;
  nextTriggerCountdown$ = new BehaviorSubject<number>(0);
  intervals: PollingInterval[] = [];
  currentPollingInterval: number;
  private shouldRefreshAutomatic = true;

  @ViewChild(CountdownIntervalComponent)
  countdownIntervalComponent!: CountdownIntervalComponent;

  private readonly destroy$ = new Subject<void>();

  readonly editorOptionsPayload = {
    mode: 'tree',
    removeModes: ['text', 'table'],
    mainMenuBar: false,
    navigationBar: false,
    readOnly: true,
    statusBar: false
  };

  // ---- DI -------------------------------------------------------------------
  private readonly fb = inject(FormBuilder);
  private readonly alertService = inject(AlertService);
  private readonly explorerService = inject(MessageExplorerService);
  private readonly connectorConfigService = inject(ConnectorConfigurationService);
  private readonly bottomDrawerService = inject(BottomDrawerService);

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
  }

  ngAfterViewInit(): void {
    setTimeout(() => this.startCountdown());
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    // Stop any active session when navigating away
    if (this.sessionId) {
      this.explorerService.stopSession(this.sessionId).catch(() => {});
    }
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
      this.messages = msgs;
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        // Session expired or was evicted on the backend — reset UI cleanly
        this.sessionId = null;
        this.connectorName = '';
        this.sessionTopic = '';
        this.messages = [];
        this.paused = false;
        this.expandedIndex = null;
        this.countdownIntervalComponent?.stop();
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
        direction: result.direction,
        deviceId: result.deviceId
      });
      this.connectorName = result.connectorName;
      this.sessionTopic = result.topic;
      this.sessionDirection = result.direction;
      this.paused = false;
      this.expandedIndex = null;
      this.expandedPayload = null;
      this.messages = [];

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
    this.sessionTopic = '';
    this.sessionDirection = 'INBOUND';
    this.paused = false;
    this.expandedIndex = null;
    this.expandedPayload = null;
    this.countdownIntervalComponent?.stop();
    this.alertService.info('Explorer session stopped.');
  }

  async onClear(): Promise<void> {
    if (this.sessionId) {
      await this.explorerService.clearMessages(this.sessionId).catch(() => {});
    }
    this.messages = [];
    this.expandedIndex = null;
    this.expandedPayload = null;
  }

  toggleExpand(index: number, payload: string): void {
    if (this.expandedIndex === index) {
      this.expandedIndex = null;
      this.expandedPayload = null;
    } else {
      this.expandedIndex = index;
      try {
        this.expandedPayload = { isJson: true, parsed: JSON.parse(payload), raw: payload };
      } catch {
        this.expandedPayload = { isJson: false, parsed: null, raw: payload };
      }
    }
  }

  truncate(text: string, maxLen = 120): string {
    return text && text.length > maxLen ? text.substring(0, maxLen) + '…' : text;
  }
}
