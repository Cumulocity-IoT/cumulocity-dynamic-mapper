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
  ElementRef,
  EventEmitter,
  inject,
  Input,
  OnDestroy,
  OnInit,
  Output,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { AlertService } from '@c8y/ngx-components';
import { BsModalService } from 'ngx-bootstrap/modal';
import { BehaviorSubject, Subject, takeUntil } from 'rxjs';
import { Content } from 'vanilla-jsoneditor';
import {
  ConfirmationModalComponent,
  Direction,
  getSchema,
  JsonEditorComponent,
  Mapping,
  MappingType,
  StepperConfiguration,
  TransformationType,
  isSubstitutionsAsCode
} from '../../shared/';
import { DynamicMapperRequest, ProcessingContext, TestResult, TOKEN_TOPIC_LEVEL } from '../core/processor/processor.model';
import { TestingService } from '../core/testing.service';
import { patchC8YTemplateForTesting, sortObjectKeys } from '../shared/util';

interface TestingModel {
  payload?: any;
  results: DynamicMapperRequest[];
  selectedResult: number;
  request?: any;
  response?: any;
  errorMsg?: string;
  api?: any;
}

@Component({
  selector: 'd11r-mapping-testing',
  templateUrl: 'mapping-testing.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class MappingStepTestingComponent implements OnInit, OnDestroy {
  @Input() mapping!: Mapping;
  @Input() stepperConfiguration!: StepperConfiguration;
  @Input() updateTestingTemplate!: EventEmitter<Mapping>;
  @Output() testResult = new EventEmitter<boolean>();

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
  private readonly elementRef = inject(ElementRef);
  private readonly bsModalService = inject(BsModalService);
  private readonly destroy$ = new Subject<void>();

  // State
  testContext!: ProcessingContext;
  testingModel: TestingModel = { results: [], selectedResult: -1 };
  testMapping!: Mapping;
  sourceTemplate: any;
  sourceSystem = '';
  targetSystem = '';
  selectedResult$ = new BehaviorSubject<number>(0);

  ngOnInit(): void {
    this.initializeMapping();
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
      await this.initializeTestContext(this.testMapping);
      this.testingService.initializeCache(this.mapping.direction);

      if (this.mapping.transformationType === TransformationType.SMART_FUNCTION) {
        await this.testingService.resetMockCache();
      }
    } catch (error) {
      this.handleError('Failed to reset transformation', error);
    }
  }

  async onNextTestResult(): Promise<void> {
    const nextIndex = this.getNextVisibleResultIndex();
    this.displayTestResult(nextIndex);
  }

  onSourceTemplateChanged(content: Content): void {
    const contentAsJson = this.parseJsonContent(content);
    const topicSample = this.extractTopicSample(contentAsJson);

    this.updateTestMapping({
      ...this.testMapping,
      sourceTemplate: JSON.stringify(contentAsJson || {}),
      mappingTopicSample: topicSample
    });
  }

  disableTestSending(): boolean {
    return !this.stepperConfiguration.allowTestSending ||
      this.testingModel.results.length === 0 ||
      !this.testMapping.useExternalId;
  }

  // ===== PRIVATE METHODS =====

  private initializeMapping(): void {
    this.mapping.direction = this.mapping.direction ?? Direction.INBOUND;
    const isInbound = this.mapping.direction === Direction.INBOUND;
    this.sourceSystem = isInbound ? 'Broker' : 'Cumulocity';
    this.targetSystem = isInbound ? 'Cumulocity' : 'Broker';
  }

  private setupSubscriptions(): void {
    this.updateTestingTemplate
      .pipe(takeUntil(this.destroy$))
      .subscribe(mapping => this.updateTestMapping(mapping));
  }

  private async updateTestMapping(testMapping: Mapping): Promise<void> {
    try {
      this.testMapping = testMapping;
      this.sourceTemplate = JSON.parse(testMapping.sourceTemplate);

      if (testMapping.direction === Direction.OUTBOUND) {
        sortObjectKeys(this.sourceTemplate);
      }

      await this.initializeTestContext(testMapping);
    } catch (error) {
      this.handleError('Failed to update test mapping', error);
    }
  }

  private async initializeTestContext(testMapping: Mapping): Promise<void> {
    this.testContext = this.testingService.initializeContext(testMapping);

    if (this.isEditorAvailable()) {
      this.resetTestingModel();
    }
  }

  private isEditorAvailable(): boolean {
    return !!this.elementRef.nativeElement.querySelector('#editorTestingRequest');
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
    this.editorTestingRequest?.set(sortObjectKeys(this.testingModel.request));
    this.editorTestingResponse?.set(sortObjectKeys(this.testingModel.response));
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
      this.testingModel.response = result.response ? sortObjectKeys(result.response) : {};
      this.testingModel.errorMsg = result.error;
    } else {
      this.testingModel.request = {};
      this.testingModel.response = {};
      this.testingModel.errorMsg = undefined;
    }
  }

  private async executeTest(sendPayload: boolean): Promise<void> {
    try {
      this.showTestWarning();

      const result = await this.performTest(sendPayload);
      this.handleTestResult(result, sendPayload);

      if (this.testingModel.results.length > 0) {
        this.displayTestResult(0);
      }
    } catch (error) {
      this.handleError('Test execution failed', error);
    }
  }

  private showTestWarning(): void {
    const { mappingType } = this.testContext.mapping;

    if (mappingType === MappingType.HEX || mappingType === MappingType.FLAT_FILE) {
      this.alertService.info(
        "Validate the mapping logic with real payloads. The specific parsing of whitespace and " +
        "line terminators (CR/LF) may differ from the test environment, potentially altering the results."
      );
    }
  }

  private async performTest(sendPayload: boolean): Promise<TestResult> {
    this.testContext.sendPayload = sendPayload;
    this.testContext = await this.testingService.testResult(
      this.testContext,
      this.sourceTemplate
    );
    this.testingModel.results = this.testContext.requests;

    const errors = [
      ...(this.testContext.errors ?? []),
      ...(this.testContext.requests ?? [])
        .filter(req => req?.error)
        .map(req => req.error)
    ];

    return {
      success: errors.length === 0,
      errors,
      warnings: this.testContext.warnings,
      requests: this.testContext.requests
    };
  }

  private handleTestResult(result: TestResult, sendPayload: boolean): void {
    if (!result.success) {
      result.errors.forEach(error => this.alertService.danger(error));
      if (sendPayload) {
        this.testResult.emit(false);
      }
      return;
    }

    if (result.warnings?.length > 0) {
      result.warnings.forEach(warning => {
        this.alertService.warning(`Test completed with warning: ${warning}`);
      });
      return;
    }

    if (sendPayload) {
      const responseId = this.testContext.requests?.[0]?.response?.id;
      this.alertService.info(`Sending mapping result was successful: ${responseId}`);
      this.testResult.emit(true);
    } else {
      this.alertService.success(`Test of mapping ${this.testMapping.name} was successful.`);
    }
  }

  private async handleError(message: string, error: unknown): Promise<void> {
    // Check if this is a non-existing device error that can be ignored
    if (this.isIgnorableDeviceError(error)) {
      const shouldIgnore = await this.confirmIgnoreDeviceError();

      if (shouldIgnore) {
        this.testMapping.createNonExistingDevice = true;
        await this.initializeTestContext(this.testMapping);
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
    // Handle direct error parameter
    if (error instanceof Error) {
      return error.message;
    }

    // Handle errors from test context
    const contextErrors = this.testContext?.errors;
    if (contextErrors?.length > 0) {
      const lastError = contextErrors[contextErrors.length - 1];
      return this.getErrorString(lastError);
    }

    return '';
  }

  private getErrorString(error: unknown): string {
    if (error instanceof Error) {
      return error.message;
    }
    return String(error);
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

  private extractTopicSample(contentAsJson: any): string {
    if (!contentAsJson?.[TOKEN_TOPIC_LEVEL] || !Array.isArray(contentAsJson[TOKEN_TOPIC_LEVEL])) {
      return '';
    }

    return contentAsJson[TOKEN_TOPIC_LEVEL]
      .filter(item => item !== undefined && item !== null)
      .join('/');
  }
}