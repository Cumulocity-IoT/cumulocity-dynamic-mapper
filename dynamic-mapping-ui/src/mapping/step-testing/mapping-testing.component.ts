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
import * as _ from 'lodash';
import { AlertService } from '@c8y/ngx-components';
import { BehaviorSubject } from 'rxjs';
import {
  Direction,
  JsonEditorComponent,
  Mapping,
  getSchema
} from '../../shared/';
import { MappingService } from '../core/mapping.service';
import { C8YRequest, ProcessingContext } from '../core/processor/processor.model';
import { StepperConfiguration } from 'src/shared/mapping/shared.model';
import { isDisabled, patchC8YTemplateForTesting } from '../shared/util';

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

  testContext: ProcessingContext;
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
  testMapping: Mapping;

  selectedResult$: BehaviorSubject<number> = new BehaviorSubject<number>(0);
  sourceSystem: string;
  targetSystem: string;
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
  sourceTemplate: any;

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

    this.editorTestingPayloadTemplateEmitter.subscribe((testMapping) => {
      this.testMapping = testMapping;
      // prepare local data add c8y data for testing: source.id
      this.sourceTemplate = JSON.parse(testMapping.sourceTemplate);
      if (testMapping.direction == Direction.OUTBOUND) patchC8YTemplateForTesting(this.sourceTemplate, this.testMapping);

      this.testContext = this.mappingService.initializeContext(testMapping);
      const editorTestingRequestRef =
        this.elementRef.nativeElement.querySelector('#editorTestingRequest');
      if (editorTestingRequestRef != null) {
        // set schema for editors
        this.editorTestingRequest.setSchema(
          getSchema(this.mapping.targetAPI, this.mapping.direction, true, true)
        );
        this.testingModel = {
          payload: this.sourceTemplate,
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
    this.testContext.sendPayload = false;
    this.testContext = await this.mappingService.testResult(
      this.testContext, this.sourceTemplate
    );
    this.testingModel.results = this.testContext.requests;
    const errors = [];
    this.testContext.requests?.forEach((r) => {
      if (r?.error) {
        errors.push(r?.error);
      }
    });
    if (this.testContext.errors.length > 0 || errors.length > 0) {
      this.alertService.warning('Testing transformation was not successful!');
      this.testContext.errors.forEach((msg) => {
        this.alertService.danger(msg);
      });
    } else {
      this.alertService.success('Testing transformation was successful.');
    }
    this.onNextTestResult();
  }

  async onSendTest() {
    this.testContext.sendPayload = true;
    this.testContext = await this.mappingService.testResult(
      this.testContext, this.sourceTemplate
    );
    this.testingModel.results = this.testContext.requests;
    const errors = [];
    this.testContext.requests?.forEach((r) => {
      if (r?.error) {
        errors.push(r?.error);
      }
    });
    if (this.testContext.errors.length > 0 || errors.length > 0) {
      this.alertService.warning('Testing transformation was not successful!');
      this.testContext.errors.forEach((msg) => {
        this.alertService.danger(msg);
      });
      this.testResult.emit(false);
    } else {
      this.alertService.info(
        `Sending transformation was successful: ${this.testContext.requests[0].response.id}`
      );
      this.testResult.emit(true);
      // console.log("RES", testProcessingContext.requests[0].response);
    }
    this.onNextTestResult();
  }

  onResetTransformation() {
    patchC8YTemplateForTesting(this.sourceTemplate, this.testMapping);
    this.testingModel = {
      payload: this.sourceTemplate,
      results: [],
      selectedResult: -1,
      request: {},
      response: {}
    };
    this.editorTestingRequest.set(this.testingModel.request);
    this.editorTestingResponse.set(this.testingModel.response);
    this.mappingService.initializeCache(this.mapping.direction);
  }

  onNextTestResult() {
    const { testingModel } = this;
    const { results, selectedResult } = testingModel;
    
    // Function to find next visible result
    const findNextVisibleResult = (currentIndex: number): number => {
        let nextIndex = currentIndex;
        do {
            nextIndex = (nextIndex >= results.length - 1) ? 0 : nextIndex + 1;
            if (!results[nextIndex].hide) {
                return nextIndex;
            }
        } while (nextIndex !== currentIndex);
        
        // If all results are hidden, return the current index
        return currentIndex;
    };

    // Update selected result index with wrapping, skipping hidden results
    testingModel.selectedResult = findNextVisibleResult(selectedResult);

    this.selectedResult$.next(testingModel.selectedResult + 1);

    // Check if selected result is valid
    const currentResult = results[testingModel.selectedResult];
    
    if (currentResult) {
        // Valid result - set properties from current result
        const { request, response, targetAPI, error } = currentResult;
        
        testingModel.request = request;
        testingModel.response = response;
        testingModel.errorMsg = error;
        
        this.editorTestingRequest.setSchema(
            getSchema(
                targetAPI,
                this.mapping.direction,
                true,
                true
            )
        );
    } else {
        // Invalid result - reset properties
        testingModel.request = {};
        testingModel.response = {};
        testingModel.errorMsg = undefined;
    }
}

  ngOnDestroy() {
    this.selectedResult$.complete();
  }
}
