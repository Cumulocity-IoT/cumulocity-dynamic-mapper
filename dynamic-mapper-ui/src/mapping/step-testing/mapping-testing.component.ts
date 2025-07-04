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
  Input,
  OnDestroy,
  OnInit,
  Output,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import * as _ from 'lodash';
import { AlertService } from '@c8y/ngx-components';
import { BehaviorSubject } from 'rxjs';
import {
  ConfirmationModalComponent,
  Direction,
  JsonEditorComponent,
  Mapping,
  getSchema
} from '../../shared/';
import { MappingService } from '../core/mapping.service';
import { C8YRequest, ProcessingContext } from '../core/processor/processor.model';
import { MappingType, StepperConfiguration } from '../../shared/mapping/mapping.model';
import { patchC8YTemplateForTesting, sortObjectKeys } from '../shared/util';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';

interface TestingModel {
  payload?: any;
  results: C8YRequest[];
  errorMsg?: string;
  request?: any;
  response?: any;
  selectedResult: number;
}

interface TestResult {
  success: boolean;
  errors: string[];
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
  @Input() updateTestingTemplate!: EventEmitter<any>;
  @Output() testResult: EventEmitter<boolean> = new EventEmitter<boolean>();

  @ViewChild('editorTestingPayload') editorTestingPayload!: JsonEditorComponent;
  @ViewChild('editorTestingRequest') editorTestingRequest!: JsonEditorComponent;
  @ViewChild('editorTestingResponse') editorTestingResponse!: JsonEditorComponent;

  readonly Direction = Direction;
  readonly MappingType = MappingType;

  private destroy$ = new BehaviorSubject<void>(undefined);

  private readonly defaultEditorOptions = {
    mode: 'tree',
    removeModes: ['text', 'table'],
    mainMenuBar: true,
    navigationBar: false,
    statusBar: false,
    readOnly: true
  } as const;

  testContext!: ProcessingContext;
  testingModel: TestingModel = { results: [], selectedResult: -1 };
  testMapping!: Mapping;
  sourceTemplate: any;
  sourceSystem!: string;
  targetSystem!: string;
  selectedResult$ = new BehaviorSubject<number>(0);
  editorOptionsTesting = this.defaultEditorOptions;
  ignoreErrorNonExisting = false;

  constructor(
    private readonly mappingService: MappingService,
    private readonly alertService: AlertService,
    private readonly elementRef: ElementRef,
    private readonly bsModalService: BsModalService,
  ) {}

  ngOnInit(): void {
    this.initializeMapping();
    this.setupSubscriptions();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.selectedResult$.complete();
  }

  // --- Public API ---

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
      this.mappingService.initializeCache(this.mapping.direction);
    } catch (error) {
      await this.handleError('Failed to reset transformation', error);
    }
  }

  async onNextTestResult(): Promise<void> {
    try {
      const nextIndex = this.calculateNextVisibleResultIndex();
      this.updateTestResult(nextIndex);
    } catch (error) {
      await this.handleError('Failed to process next test result', error);
    }
  }

  disableTestSending(): boolean {
    return !this.stepperConfiguration.allowTestSending ||
      this.testingModel.results.length === 0 ||
      !this.testMapping.useExternalId;
  }

  // --- Private methods ---

  private initializeMapping(): void {
    this.mapping.direction = this.mapping.direction ?? Direction.INBOUND;
    this.setSystemDirections();
  }

  private setSystemDirections(): void {
    const isInbound = this.mapping.direction === Direction.INBOUND;
    this.targetSystem = isInbound ? 'Cumulocity' : 'Broker';
    this.sourceSystem = !isInbound ? 'Cumulocity' : 'Broker';
  }

  private setupSubscriptions(): void {
    this.updateTestingTemplate
      .pipe()
      .subscribe(this.handleTestMappingUpdate.bind(this));
  }

  private async handleTestMappingUpdate(testMapping: Mapping): Promise<void> {
    try {
      this.testMapping = testMapping;
      this.sourceTemplate = JSON.parse(testMapping.sourceTemplate);

      if (testMapping.direction === Direction.OUTBOUND) {
        patchC8YTemplateForTesting(this.sourceTemplate, this.testMapping);
        sortObjectKeys(this.sourceTemplate);
      }

      await this.initializeTestContext(testMapping);
    } catch (error) {
      await this.handleError('Failed to update test mapping', error);
    }
  }

  private async initializeTestContext(testMapping: Mapping): Promise<void> {
    this.testContext = this.mappingService.initializeContext(testMapping);

    if (this.isEditorAvailable()) {
      await this.setupEditor();
    }
  }

  private isEditorAvailable(): boolean {
    return !!this.elementRef.nativeElement.querySelector('#editorTestingRequest');
  }

  private async setupEditor(): Promise<void> {
    const schema = getSchema(
      this.mapping.targetAPI,
      this.mapping.direction,
      true,
      true
    );
    this.editorTestingRequest?.setSchema(schema);
    this.resetTestingModel();
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

  private calculateNextVisibleResultIndex(): number {
    const { results, selectedResult } = this.testingModel;
    let nextIndex = selectedResult;

    do {
      nextIndex = (nextIndex >= results.length - 1) ? 0 : nextIndex + 1;
    } while (nextIndex !== selectedResult && results[nextIndex]?.hidden);

    return nextIndex;
  }

  private updateTestResult(index: number): void {
    this.testingModel.selectedResult = index;
    this.selectedResult$.next(index + 1);

    const currentResult = sortObjectKeys(this.testingModel.results[index]);
    if (currentResult) {
      this.updateTestingModelFromResult(currentResult);
    } else {
      this.resetTestingModelProperties();
    }
  }

  private updateTestingModelFromResult(result: C8YRequest): void {
    const { request, response, targetAPI, error } = result;
    this.testingModel.request = sortObjectKeys(request);
    this.testingModel.response = sortObjectKeys(response);
    this.testingModel.errorMsg = error;

    this.editorTestingRequest?.setSchema(
      getSchema(targetAPI, this.mapping.direction, true, true)
    );
  }

  private resetTestingModelProperties(): void {
    this.testingModel.request = {};
    this.testingModel.response = {};
    this.testingModel.errorMsg = undefined;
  }

  private async executeTest(sendPayload: boolean): Promise<void> {
    try {
      const result = await this.performTestExecution(sendPayload);
      this.handleTestResult(result, sendPayload);
      this.onNextTestResult();
    } catch (error) {
      await this.handleError('Test execution failed', error);
    }
  }

  private async performTestExecution(sendPayload: boolean): Promise<TestResult> {
    this.testContext.sendPayload = sendPayload;

    // Update test context
    this.testContext = await this.mappingService.testResult(
      this.testContext,
      this.sourceTemplate
    );

    // Update testing model
    this.testingModel.results = this.testContext.requests;

    // Collect all errors
    const requestErrors = this.collectRequestErrors();
    const contextErrors = this.testContext.errors ?? [];

    return {
      success: requestErrors.length === 0 && contextErrors.length === 0,
      errors: [...contextErrors, ...requestErrors]
    };
  }

  private collectRequestErrors(): string[] {
    return (this.testContext.requests ?? [])
      .filter(request => request?.error)
      .map(request => request.error);
  }

  private handleTestResult(result: TestResult, sendPayload: boolean): void {
    if (!result.success) {
      this.handleTestFailure(result.errors);
      if (sendPayload) {
        this.testResult.emit(false);
      }
    } else {
      this.handleTestSuccess(sendPayload);
    }
  }

  private handleTestFailure(errors: string[]): void {
    errors.forEach(error => {
      this.alertService.danger(error);
    });
  }

  private handleTestSuccess(sendPayload: boolean): void {
    if (sendPayload) {
      const responseId = this.testContext.requests?.[0]?.response?.id;
      this.alertService.info(
        `Sending mapping result was successful: ${responseId}`
      );
      this.testResult.emit(true);
    } else {
      this.alertService.success(`Test of mapping ${this.testMapping.name} was successful.`);
    }
  }

  private async handleError(message: string, error: unknown): Promise<void> {
    let result = false;
    if (typeof error === 'object' && error && 'possibleIgnoreErrorNonExisting' in error) {
      const initialState = {
        title: `Ignore error non existing device`,
        message: `The testing resulted in an error, that the referenced device does not exist! Would you like to test again and ignore this error?`,
        labels: {
          ok: 'Ignore',
          cancel: 'Cancel'
        }
      } as const;
      const confirmDeletionModalRef: BsModalRef = this.bsModalService.show(
        ConfirmationModalComponent,
        { initialState }
      );

      result = await confirmDeletionModalRef.content.closeSubject.toPromise();
      if (result) {
        this.testMapping.createNonExistingDevice = true;
        await this.initializeTestContext(this.testMapping);
      }
    } else {
      const m = message || (error instanceof Error ? error.message : String(error));
      this.alertService.danger(`${m}`);
    }
  }
}
