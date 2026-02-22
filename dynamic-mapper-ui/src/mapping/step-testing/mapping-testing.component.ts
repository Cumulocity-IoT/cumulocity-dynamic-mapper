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
import { BehaviorSubject, Subject, takeUntil } from 'rxjs';
import { Content } from 'vanilla-jsoneditor';
import {
  ConfirmationModalComponent,
  ContentChanges,
  Direction,
  JsonEditorComponent,
  Mapping,
  MappingType,
  StepperConfiguration,
  isSubstitutionsAsCode
} from '../../shared/';
import { DynamicMapperRequest, TestResult, TestContext, MappingTokens } from '../core/processor/processor.model';
import { TestingService } from '../core/testing.service';
import { patchC8YTemplateForTesting, sortObjectKeys } from '../shared/util';
import { CollapseModule } from 'ngx-bootstrap/collapse';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { CommonModule } from '@angular/common';

interface TestingModel {
  payload?: any;
  results: DynamicMapperRequest[];
  selectedResult: number;
  request?: any;
  response?: any;
  errorMsg?: string;
  api?: any;
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
  @Input() updateTestingTemplate!: EventEmitter<Mapping>;
  @Output() testResult = new EventEmitter<boolean>();
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
    removeModes: ['text', 'table'],
    mainMenuBar: true,
    navigationBar: false,
    statusBar: false,
    readOnly: true
  } as const;

  readonly editorOptionsSource = {
    mode: 'tree',
    removeModes: ['table'],
    mainMenuBar: true,
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
  testingModel: TestingModel = { results: [], selectedResult: -1 };
  testMapping!: Mapping;
  sourceTemplate: any;
  sourceSystem = '';
  targetSystem = '';
  selectedResult$ = new BehaviorSubject<number>(0);
  hasResponse = true; // Tracks if current result has a response

  async ngOnInit(): Promise<void> {
    this.initializeMapping();
    await this.testingService.resetMockCache();
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

  private requiresRawPayload(): boolean {
    return this.testMapping.mappingType === MappingType.HEX ||
           this.testMapping.mappingType === MappingType.FLAT_FILE;
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
    this.testingModel = {
      payload: this.sourceTemplate,
      results: [],
      selectedResult: -1,
      request: {},
      response: {},
    };
  }

  private updateEditors(): void {
    // Data is already sorted in displayTestResult(), pass it directly
    this.editorTestingRequest?.set(this.testingModel.request);
    // Handle null response for test mode (don't try to sort null)
    this.editorTestingResponse?.set(this.testingModel.response);
  }

  private getNextVisibleResultIndex(): number {
    const { results, selectedResult } = this.testingModel;
    let nextIndex = selectedResult;

    do {
      nextIndex = (nextIndex >= results.length - 1) ? 0 : nextIndex + 1;
    } while (nextIndex !== selectedResult && results[nextIndex]?.hidden);

    return nextIndex;
  }

  private displayTestResult(index: number): void {
    this.testingModel.selectedResult = index;
    this.selectedResult$.next(index + 1);

    const result = this.testingModel.results[index];

    if (result) {
      this.testingModel.request = sortObjectKeys(result.request);
      this.testingModel.api = result.api;

      // Clear response if not present (test mode) - show empty object
      if (result.response) {
        this.testingModel.response = sortObjectKeys(result.response);
        this.hasResponse = true;
      } else {
        // Show empty object and info message for test mode
        this.testingModel.response = {};
        this.hasResponse = false;
      }

      this.testingModel.errorMsg = result.error;
    } else {
      this.testingModel.request = {};
      this.testingModel.response = {};
      this.testingModel.errorMsg = undefined;
      this.hasResponse = false;
    }

    // Force editor update to clear stale content when switching between results
    this.updateEditors();
  }

  private async executeTest(sendPayload: boolean): Promise<void> {
    try {
      this.showTestWarning();

      const result = await this.performTest(sendPayload);
      await this.handleTestResult(result, sendPayload);

      if (this.testingModel.results.length > 0) {
        this.displayTestResult(0);
      }
    } catch (error) {
      this.handleError('Test execution failed', error);
    }
  }

  private showTestWarning(): void {
    if (this.requiresRawPayload()) {
      this.alertService.info(
        "Validate the mapping logic with real payloads. The specific parsing of whitespace and " +
        "line terminators (CR/LF) may differ from the test environment, potentially altering the results."
      );
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
      send: sendPayload
    };

    const result = await this.testingService.testMapping(testContext);

    // Convert request and response from JSON string to object for all items
    this.testingModel.results = result.requests.map(req => this.parseRequestResponse(req));
    this.testingModel.logs = result.logs;

    return result;
  }

  private async handleTestResult(result: TestResult, sendPayload: boolean): Promise<void> {
    if (!result.success) {
      result.errors.forEach(error => this.alertService.danger(error));
      if (sendPayload) {
        this.testResult.emit(false);
      }
      return;
    }

    if (result.warnings?.length > 0) {
      const createDeviceWarning = result.warnings.find(w =>
        w.includes('createNonExistingDevice is disabled')
      );
      if (createDeviceWarning) {
        const shouldEnable = await this.confirmEnableCreateNonExistingDevice();
        if (shouldEnable) {
          this.testMapping.createNonExistingDevice = true;
          await this.executeTest(sendPayload);
          return;
        }
      }
      result.warnings
        .filter(w => !w.includes('createNonExistingDevice is disabled'))
        .forEach(warning => {
          this.alertService.warning(`Test completed with warning: ${warning}`);
        });
      return;
    }

    if (sendPayload) {
      const responseId = result.requests?.[0]?.response?.id;
      this.alertService.info(`Sending mapping result was successful: ${responseId}`);
      this.testResult.emit(true);
    } else {
      this.alertService.success(`Test of mapping ${this.testMapping.name} was successful.`);
    }
  }

  private async confirmEnableCreateNonExistingDevice(): Promise<boolean> {
    const modalRef = this.bsModalService.show(ConfirmationModalComponent, {
      initialState: {
        title: 'Enable device creation during testing',
        message: 'Do you want to set createNonExistingDevice during testing to true?',
        labels: { ok: 'Enable', cancel: 'Cancel' }
      }
    });
    return await modalRef.content.closeSubject.toPromise();
  }

  private async handleError(message: string, error: unknown): Promise<void> {
    // Check if this is a non-existing device error that can be ignored
    if (this.isIgnorableDeviceError(error)) {
      const shouldIgnore = await this.confirmIgnoreDeviceError();

      if (shouldIgnore) {
        this.testMapping.createNonExistingDevice = true;
        await this.executeTest(false);
        return;
      }
    }

    // Display error message
    const errorMsg = this.extractErrorMessage(error);
    const fullMessage = errorMsg ? `${message}: ${errorMsg}` : message;
    this.alertService.danger(fullMessage);
  }

  private isIgnorableDeviceError(error: unknown): boolean {
    return typeof error === 'object' &&
      error !== null &&
      'possibleIgnoreErrorNonExisting' in error;
  }

  private async confirmIgnoreDeviceError(): Promise<boolean> {
    const modalRef = this.bsModalService.show(ConfirmationModalComponent, {
      initialState: {
        title: 'Ignore error non existing device',
        message: 'The testing resulted in an error, that the referenced device does not exist! ' +
          'Would you like to test again and ignore this error?',
        labels: { ok: 'Ignore', cancel: 'Cancel' }
      }
    });

    return await modalRef.content.closeSubject.toPromise();
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
    if (line.startsWith('JS ERROR:')) return 'error';
    if (line.startsWith('JS WARN:')) return 'warn';
    if (line.startsWith('JS DEBUG:')) return 'debug';
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