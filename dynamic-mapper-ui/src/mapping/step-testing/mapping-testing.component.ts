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
  Component,
  EventEmitter,
  inject,
  Input,
  OnDestroy,
  OnInit,
  Output,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { AlertService, CoreModule } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { BehaviorSubject, firstValueFrom, ReplaySubject, Subject, takeUntil } from 'rxjs';
import { Content } from 'vanilla-jsoneditor';
import {
  ConfirmationModalComponent,
  ContentChanges,
  Direction,
  JsonEditorComponent,
  Mapping,
  MappingType,
  StepperConfiguration,
  isSubstitutionsAsCode,
  ALERT_INFO_TIMEOUT
} from '../../shared/';
import { DynamicMapperRequest, TestResult, TestContext, MappingTokens } from '../core/processor/processor.model';
import { TestingService } from '../core/testing.service';
import { patchC8YTemplateForTesting, sortObjectKeys } from '../shared/util';
import { CollapseModule } from 'ngx-bootstrap/collapse';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { CommonModule } from '@angular/common';

interface TestingModel {
  results: DynamicMapperRequest[];
  request?: any;
  response?: any;
  logs?: string[];
}

@Component({
  selector: 'd11r-mapping-testing',
  templateUrl: 'mapping-testing.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, CommonModule, PopoverModule, CollapseModule, JsonEditorComponent]


})
export class MappingStepTestingComponent implements OnInit, OnDestroy {
  @Input() mapping!: Mapping;
  @Input() stepperConfiguration!: StepperConfiguration;
  @Input() updateTestingTemplate!: ReplaySubject<Mapping>;
  @Output() sourceTemplateChanged = new EventEmitter<any>();

  @ViewChild('editorTestingPayload') editorTestingPayload!: JsonEditorComponent;
  @ViewChild('editorTestingRequest') editorTestingRequest!: JsonEditorComponent;
  @ViewChild('editorTestingResponse') editorTestingResponse!: JsonEditorComponent;

  // Template helpers
  readonly Direction = Direction;
  readonly MappingType = MappingType;
  readonly isSubstitutionsAsCode = isSubstitutionsAsCode;

  // Editor configurations
  readonly editorOptionsDefault = {
    mode: 'tree',
  removeModes: ['table'],
    mainMenuBar: true,
    navigationBar: false,
    statusBar: false,
    readOnly: true
  } as const;

  readonly editorOptionsSource = {
    mode: 'tree',
    mainMenuBar: true,
    removeModes: ['table'],
    navigationBar: false,
    statusBar: false,
    readOnly: false
  } as const;

  // Services
  private readonly testingService = inject(TestingService);
  private readonly alertService = inject(AlertService);
  private readonly bsModalService = inject(BsModalService);
  private readonly destroy$ = new Subject<void>();

  // State
  testingModel: TestingModel = { results: [] };
  createTestDevice = false;
  testMapping!: Mapping;
  sourceTemplate: any;
  sourceSystem = '';
  targetSystem = '';
  selectedResult$ = new BehaviorSubject<number>(-1);
  isLoading = false; // Tracks whether a test request is in progress
  showResponse = false; // Controls collapsible response section
  showConsole = true; // Controls collapsible console section (expanded by default)
  currentApi: string | undefined;
  currentPublishTopic: string | undefined;

  async ngOnInit(): Promise<void> {
    this.initializeMapping();
    try {
      await this.testingService.resetMockCache();
    } catch (error) {
      this.handleError('Failed to clear cache', error);
    }
    this.setupSubscriptions();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.selectedResult$.complete();
  }

  // ===== PUBLIC API =====

  async onTestTransformation(): Promise<void> {
    await this.executeTest(false);
  }

  async onSendTest(): Promise<void> {
    await this.executeTest(true);
  }

  async onResetTransformation(): Promise<void> {
    try {
      patchC8YTemplateForTesting(this.sourceTemplate, this.testMapping);
      this.resetTestingModel();
      this.updateEditors();

      await this.testingService.resetMockCache();
    } catch (error) {
      this.handleError('Failed to reset transformation', error);
    }
  }

  onNextTestResult(): void {
    const nextIndex = this.getNextVisibleResultIndex();
    this.displayTestResult(nextIndex);
  }

  onSourceTemplateChanged(content: ContentChanges): void {
    const contentAsJson = this.parseJsonContent(content.updatedContent);
    this.syncPayload(contentAsJson, this.extractTopicSample(contentAsJson));
  }

  disableTestSending(): boolean {
    return !this.stepperConfiguration.allowTestSending ||
      this.testingModel.results.length === 0 ||
      !this.testMapping.useExternalId;
  }

  // ===== PRIVATE METHODS =====

  protected requiresRawPayload(): boolean {
    return this.testMapping?.mappingType === MappingType.HEX ||
           this.testMapping?.mappingType === MappingType.FLAT_FILE;
  }

  private initializeMapping(): void {
    // Ensure direction is always set (fallback to INBOUND if not specified)
    this.mapping.direction = this.mapping.direction ?? Direction.INBOUND;
    const isInbound = this.mapping.direction === Direction.INBOUND;
    this.sourceSystem = isInbound ? 'Broker' : 'Cumulocity';
    this.targetSystem = isInbound ? 'Cumulocity' : 'Broker';

    // Initialize testMapping with the full mapping object
    this.updateTestMapping(this.mapping);

    // Initialize testing model with the payload
    this.resetTestingModel();
  }

  private setupSubscriptions(): void {
    this.updateTestingTemplate
      .pipe(takeUntil(this.destroy$))
      .subscribe(mapping => {
        this.updateTestMapping(mapping);
        this.resetTestingModel();
      });
  }

  private updateTestMapping(testMapping: Mapping): void {
    try {
      this.testMapping = testMapping;
      this.sourceTemplate = JSON.parse(testMapping.sourceTemplate);

      if (testMapping.direction === Direction.OUTBOUND) {
        sortObjectKeys(this.sourceTemplate);
      }
    } catch (error) {
      this.handleError('Failed to update test mapping', error);
    }
  }

  private resetTestingModel(): void {
    const logs: string[] = this.requiresRawPayload()
      ? ['INFO Validate the mapping logic with real payloads. The specific parsing of whitespace and line terminators (CR/LF) may differ from the test environment, potentially altering the results.']
      : [];
    this.testingModel = { results: [], request: {}, response: {}, logs };
    this.selectedResult$.next(-1);
    this.currentApi = undefined;
    this.currentPublishTopic = undefined;
  }

  private updateEditors(): void {
    // Data is already sorted in displayTestResult(), pass it directly
    this.editorTestingRequest?.set(this.testingModel.request);
    // Handle null response for test mode (don't try to sort null)
    this.editorTestingResponse?.set(this.testingModel.response);
  }

  private getNextVisibleResultIndex(): number {
    const { results } = this.testingModel;
    const current = this.selectedResult$.getValue();
    let nextIndex = current;

    do {
      nextIndex = (nextIndex >= results.length - 1) ? 0 : nextIndex + 1;
    } while (nextIndex !== current && results[nextIndex]?.hidden);

    return nextIndex;
  }

  private displayTestResult(index: number): void {
    this.selectedResult$.next(index);

    const result = this.testingModel.results[index];

    if (result) {
      this.testingModel.request = sortObjectKeys(result.request);
      this.currentApi = result.api;
      this.currentPublishTopic = result.publishTopic;

      if (result.response) {
        this.testingModel.response = sortObjectKeys(result.response);
      } else {
        this.testingModel.response = {};
        const testModeMsg = 'INFO No response in test mode. Data operations (MEASUREMENT, EVENT, ALARM) are prepared but not executed. Use "Send Test Message" to get actual responses.';
        if (!this.testingModel.logs) {
          this.testingModel.logs = [];
        }
        if (!this.testingModel.logs.includes(testModeMsg)) {
          this.testingModel.logs = [testModeMsg, ...this.testingModel.logs.filter(l => l !== testModeMsg)];
        }
      }
    } else {
      this.testingModel.request = {};
      this.testingModel.response = {};
    }

    this.updateEditors();
  }

  private async executeTest(sendPayload: boolean): Promise<void> {
    this.isLoading = true;
    try {
      const result = await this.performTest(sendPayload);
      await this.handleTestResult(result, sendPayload);

      if (this.testingModel.results.length > 0) {
        this.displayTestResult(0);
      }
    } catch (error) {
      this.handleError('Test execution failed', error);
    } finally {
      this.isLoading = false;
    }
  }


  private parseRequestResponse(req: DynamicMapperRequest): DynamicMapperRequest {
    return {
      ...req,
      request: typeof req.request === 'string' ? JSON.parse(req.request) : req.request,
      response: typeof req.response === 'string' ? JSON.parse(req.response) : req.response
    };
  }

  // Sync all three payload representations atomically.
  // topicSample is only provided when called from the editor onChange handler.
  private syncPayload(parsedContent: any, topicSample?: string): void {
    const sourceTemplateStr = JSON.stringify(parsedContent || {});
    this.sourceTemplate = parsedContent;
    this.testMapping = {
      ...this.testMapping,
      sourceTemplate: sourceTemplateStr,
      ...(topicSample ? { mappingTopicSample: topicSample } : {})
    };
    this.mapping.sourceTemplate = sourceTemplateStr;
    this.sourceTemplateChanged.emit(parsedContent);
  }

  private async performTest(sendPayload: boolean): Promise<TestResult> {
    // Always read directly from the editor to capture the latest content,
    // regardless of whether onChange fired (tree-mode edits may not always trigger it)
    this.syncPayload(this.editorTestingPayload?.get() ?? this.sourceTemplate);

    const extractedPayload = this.requiresRawPayload()
      ? this.sourceTemplate?.['payload']
      : this.testMapping.sourceTemplate;

    // Create test context and call remote testing endpoint
    const testContext: TestContext = {
      mapping: this.testMapping,
      payload: extractedPayload,
      send: sendPayload,
      createTestDevice: sendPayload && this.createTestDevice
    };

    const result = await this.testingService.testMapping(testContext);

    // Convert request and response from JSON string to object for all items
    this.testingModel.results = result.requests.map(req => this.parseRequestResponse(req));
    const staticLogs = this.testingModel.logs?.filter(l => l.startsWith('INFO')) ?? [];
    const warningLogs = (result.warnings ?? []).map(w => `WARNING: ${w}`);
    this.testingModel.logs = [...staticLogs, ...(result.logs ?? []), ...warningLogs];

    return result;
  }

  private async handleTestResult(result: TestResult, sendPayload: boolean): Promise<void> {
    if (!result.success) {
      const errorLogs = (result.errors ?? []).map(e => `ERROR: ${e}`);
      this.testingModel.logs = [...(this.testingModel.logs ?? []), ...errorLogs];
      return;
    }

    if (result.warnings?.length > 0) {
      const createDeviceWarning = result.warnings.find(w =>
        w.includes('createNonExistingDevice is disabled')
      );
      if (createDeviceWarning) {
        const shouldEnable = await this.showConfirmation(
          'Enable device creation during testing',
          'Do you want to set createNonExistingDevice during testing to true?',
          { ok: 'Enable', cancel: 'Cancel' }
        );
        if (shouldEnable) {
          this.testMapping.createNonExistingDevice = true;
          await this.executeTest(sendPayload);
          return;
        }
      }
    }

    if (sendPayload) {
      const rawResponse = result.requests?.[0]?.response;
      const parsedResponse = typeof rawResponse === 'string' ? JSON.parse(rawResponse) : rawResponse;
      const responseId = parsedResponse?.id;
      const responseLabel = responseId ?? result.requests?.[0]?.sourceId ?? 'unknown';
      const deviceInfo = result.testDeviceId ? `, test device: ${result.testDeviceId}` : '';
      this.alertService.add({ text: `Sending mapping result was successful: ${responseLabel}${deviceInfo}`, type: 'info', timeout: ALERT_INFO_TIMEOUT });
    }
  }

  private async handleError(message: string, error: unknown): Promise<void> {
    if (typeof error === 'object' && error !== null && 'possibleIgnoreErrorNonExisting' in error) {
      const shouldIgnore = await this.showConfirmation(
        'Ignore error non existing device',
        'The testing resulted in an error, that the referenced device does not exist! Would you like to test again and ignore this error?',
        { ok: 'Ignore', cancel: 'Cancel' }
      );
      if (shouldIgnore) {
        this.testMapping.createNonExistingDevice = true;
        await this.executeTest(false);
        return;
      }
    }

    const errorMsg = this.extractErrorMessage(error);
    this.alertService.danger(errorMsg ? `${message}: ${errorMsg}` : message);
  }

  private showConfirmation(title: string, message: string, labels: { ok: string; cancel: string }): Promise<boolean> {
    const modalRef = this.bsModalService.show(ConfirmationModalComponent, { initialState: { title, message, labels } });
    return firstValueFrom(modalRef.content.closeSubject);
  }

  private extractErrorMessage(error: unknown): string {
    if (error instanceof Error) {
      return error.message;
    }
    return String(error || '');
  }

  private parseJsonContent(content: Content): any {
    if ('text' in content && content.text) {
      try {
        return JSON.parse(content.text);
      } catch {
        return null;
      }
    }
    return content['json'];
  }

  getLogLevel(line: string): string {
    if (line.startsWith('ERROR:') || line.startsWith('JS ERROR:')) return 'error';
    if (line.startsWith('JS WARN:') || line.startsWith('WARNING:')) return 'warn';
    if (line.startsWith('JS DEBUG:')) return 'debug';
    if (line.startsWith('INFO')) return 'info';
    return 'log';
  }

  private extractTopicSample(contentAsJson: any): string {
    if (!contentAsJson?.[MappingTokens.TOPIC_LEVEL] || !Array.isArray(contentAsJson[MappingTokens.TOPIC_LEVEL])) {
      return '';
    }

    return contentAsJson[MappingTokens.TOPIC_LEVEL]
      .filter(item => item !== undefined && item !== null)
      .join('/');
  }
}