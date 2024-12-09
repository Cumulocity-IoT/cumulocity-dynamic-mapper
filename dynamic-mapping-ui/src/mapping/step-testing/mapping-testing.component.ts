/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
import { AlertService } from '@c8y/ngx-components';
import { BehaviorSubject } from 'rxjs';
import {
  Direction,
  JsonEditorComponent,
  Mapping,
  getSchema
} from '../../shared/';
import { MappingService } from '../core/mapping.service';
import { C8YRequest } from '../processor/processor.model';
import { StepperConfiguration } from 'src/shared/mapping/shared.model';
import { isDisabled } from '../shared/util';

@Component({
  selector: 'd11r-mapping-testing',
  templateUrl: 'mapping-testing.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class MappingStepTestingComponent implements OnInit, OnDestroy {
  @Input() mapping: Mapping;
  @Input() stepperConfiguration: StepperConfiguration;
  @Input() editorTestingPayloadTemplateEmitter: EventEmitter<any>;

  @Output() testResult: EventEmitter<boolean> = new EventEmitter<boolean>();

  Direction = Direction;
  isDisabled = isDisabled;

  testingModel: {
    payload?: any;
    results: C8YRequest[];
    errorMsg?: string;
    request?: any;
    response?: any;
    selectedResult: number;
  } = {
      results: [],
      selectedResult: -1
    };

  selectedResult$: BehaviorSubject<number> = new BehaviorSubject<number>(0);
  sourceSystem: string;
  targetSystem: string;
  currentContext: any;
  editorOptionsTesting: any = {
    mode: 'tree',
    removeModes: ['text', 'table'],
    mainMenuBar: true,
    navigationBar: false,
    statusBar: false,
    readOnly: true
  };

  @ViewChild('editorTestingPayload', { static: false })
  editorTestingPayload: JsonEditorComponent;
  @ViewChild('editorTestingRequest', { static: false })
  editorTestingRequest: JsonEditorComponent;
  @ViewChild('editorTestingResponse', { static: false })
  editorTestingResponse: JsonEditorComponent;
  source: any;

  constructor(
    public mappingService: MappingService,
    private alertService: AlertService,
    private elementRef: ElementRef
  ) { }

  ngOnInit() {
    // set value for backward compatiblility
    if (!this.mapping.direction) this.mapping.direction = Direction.INBOUND;
    this.targetSystem =
      this.mapping.direction == Direction.INBOUND ? 'Cumulocity' : 'Broker';
    this.sourceSystem =
      this.mapping.direction == Direction.OUTBOUND ? 'Cumulocity' : 'Broker';
    // console.log(
    //  'Mapping to be tested:',
    //  this.mapping,
    //  this.stepperConfiguration
    // );

    this.editorTestingPayloadTemplateEmitter.subscribe((current) => {
      this.currentContext = current;
      this.source = JSON.parse(this.currentContext.mapping.sourceTemplate);
      this.currentContext.mapping.sourceTemplate = JSON.stringify(current.sourceTemplate);
      this.currentContext.mapping.targetTemplate = JSON.stringify(current.targetTemplate);
      const editorTestingRequestRef =
        this.elementRef.nativeElement.querySelector('#editorTestingRequest');
      if (editorTestingRequestRef != null) {
        // set schema for editors
        this.editorTestingRequest.setSchema(
          getSchema(this.mapping.targetAPI, this.mapping.direction, true, true)
        );
        this.testingModel = {
          payload: this.source,
          results: [],
          selectedResult: -1,
          request: {},
          response: {}
        };
      }
      // console.log('New test template:', this.currentSourceTemplate);
    });
  }

  async onTestTransformation() {
    const testProcessingContext = await this.mappingService.testResult(
      this.currentContext.mapping,
      false
    );
    this.testingModel.results = testProcessingContext.requests;
    const errors = [];
    testProcessingContext.requests?.forEach((r) => {
      if (r?.error) {
        errors.push(r?.error);
      }
    });
    if (testProcessingContext.errors.length > 0 || errors.length > 0) {
      this.alertService.warning('Testing transformation was not successful!');
      testProcessingContext.errors.forEach((msg) => {
        this.alertService.danger(msg);
      });
    } else {
      this.alertService.success('Testing transformation was successful.');
    }
    this.onNextTestResult();
  }

  async onSendTest() {
    const testProcessingContext = await this.mappingService.testResult(
      this.currentContext.mapping,
      true
    );
    this.testingModel.results = testProcessingContext.requests;
    const errors = [];
    testProcessingContext.requests?.forEach((r) => {
      if (r?.error) {
        errors.push(r?.error);
      }
    });
    if (testProcessingContext.errors.length > 0 || errors.length > 0) {
      this.alertService.warning('Testing transformation was not successful!');
      testProcessingContext.errors.forEach((msg) => {
        this.alertService.danger(msg);
      });
      this.testResult.emit(false);
    } else {
      this.alertService.info(
        `Sending transformation was successful: ${testProcessingContext.requests[0].response.id}`
      );
      this.testResult.emit(true);
      // console.log("RES", testProcessingContext.requests[0].response);
    }
    this.onNextTestResult();
  }

  onResetTransformation() {
    this.testingModel = {
      payload: this.source,
      results: [],
      request: {},
      response: {},
      selectedResult: -1
    };
    this.editorTestingRequest.set(this.testingModel.request);
    this.editorTestingResponse.set(this.testingModel.response);
    this.mappingService.initializeCache(this.mapping.direction);
  }

  onNextTestResult() {
    if (
      this.testingModel.selectedResult >=
      this.testingModel.results.length - 1
    ) {
      this.testingModel.selectedResult = -1;
    }
    this.testingModel.selectedResult++;
    this.selectedResult$.next(this.testingModel.selectedResult + 1);
    if (
      this.testingModel.selectedResult >= 0 &&
      this.testingModel.selectedResult < this.testingModel.results.length
    ) {
      this.testingModel.request =
        this.testingModel.results[this.testingModel.selectedResult].request;
      this.testingModel.response =
        this.testingModel.results[this.testingModel.selectedResult].response;
      this.editorTestingRequest.setSchema(
        getSchema(
          this.testingModel.results[this.testingModel.selectedResult].targetAPI,
          this.mapping.direction,
          true, true
        )
      );
      this.testingModel.errorMsg =
        this.testingModel.results[this.testingModel.selectedResult].error;
    } else {
      this.testingModel.request = JSON.parse('{}');
      this.testingModel.response = JSON.parse('{}');
      this.testingModel.errorMsg = undefined;
    }
  }

  ngOnDestroy() {
    this.selectedResult$.complete();
  }
}
