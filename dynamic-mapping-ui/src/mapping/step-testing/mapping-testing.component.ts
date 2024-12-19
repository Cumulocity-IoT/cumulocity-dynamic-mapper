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

    this.editorTestingPayloadTemplateEmitter.subscribe((current) => {
      // prepare local data
      this.currentContext = current;
      this.currentContext.mapping.sourceTemplate = JSON.stringify(current.sourceTemplate);
      this.currentContext.mapping.targetTemplate = JSON.stringify(current.targetTemplate);

      // add c8y data for testing: source.id
      this.sourceTemplate = _.clone(current.sourceTemplate);
      if (this.currentContext.mapping.direction == Direction.OUTBOUND) patchC8YTemplateForTesting(this.sourceTemplate, this.currentContext.mapping);

      this.testContext = this.mappingService.initializeContext(this.currentContext.mapping);
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
    patchC8YTemplateForTesting(this.sourceTemplate, this.currentContext.mapping);
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
