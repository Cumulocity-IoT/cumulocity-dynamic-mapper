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
  OnInit,
  ViewChild,
  ViewEncapsulation,
} from "@angular/core";
import { AlertService } from "@c8y/ngx-components";
import * as _ from "lodash";
import { BehaviorSubject } from "rxjs";
import { Direction, Mapping } from "../../shared/mapping.model";
import { getSchema } from "../../shared/util";
import { MappingService } from "../core/mapping.service";
import { C8YRequest } from "../processor/prosessor.model";
import { isDisabled } from "../step-main/util";
import { JsonEditor2Component } from "../../shared/editor2/jsoneditor2.component";
import { StepperConfiguration } from "../step-main/stepper-model";

@Component({
  selector: "mapping-testing",
  templateUrl: "mapping-testing.component.html",
  styleUrls: ["../shared/mapping.style.css"],
  encapsulation: ViewEncapsulation.None,
})
export class MappingStepTestingComponent implements OnInit {
  @Input() mapping: Mapping;
  @Input() stepperConfiguration: StepperConfiguration;
  @Input() editorTestingRequestTemplateEmitter: EventEmitter<any>;

  Direction = Direction;
  isDisabled = isDisabled;

  testingModel: {
    results: C8YRequest[];
    errorMsg?: string;
    request?: any;
    response?: any;
    selectedResult: number;
  } = {
    results: [],
    selectedResult: -1,
  };

  selectedResult$: BehaviorSubject<number> = new BehaviorSubject<number>(0);
  sourceSystem: string;
  targetSystem: string;

  editorOptionsTesting: any = {};

  @ViewChild("editorTestingRequest", { static: false })
  editorTestingRequest: JsonEditor2Component;
  @ViewChild("editorTestingResponse", { static: false })
  editorTestingResponse: JsonEditor2Component;

  constructor(
    public mappingService: MappingService,
    private alertService: AlertService,
    private elementRef: ElementRef
  ) {}

  ngOnInit() {
    // set value for backward compatiblility
    if (!this.mapping.direction) this.mapping.direction = Direction.INBOUND;
    this.targetSystem =
      this.mapping.direction == Direction.INBOUND
        ? "Cumulocity"
        : "MQTT Broker";
    this.sourceSystem =
      this.mapping.direction == Direction.OUTBOUND
        ? "Cumulocity"
        : "MQTT Broker";
    console.log(
      "Mapping to be tested:",
      this.mapping,
      this.stepperConfiguration
    );

    this.editorOptionsTesting = {
      ...this.editorOptionsTesting,
      mode: "tree",
      mainMenuBar: true,
      navigationBar: false,
      statusBar: false,
      readOnly: true,
    };

    this.editorTestingRequestTemplateEmitter.subscribe((template) => {
      const editorTestingResponseRef =
        this.elementRef.nativeElement.querySelector("#editorTestingResponse");
      if (editorTestingResponseRef != null) {
        //set schema for editors
        this.editorTestingResponse.set({} as JSON);
        editorTestingResponseRef.setAttribute("initialized", "true");
      }

      const editorTestingRequestRef =
        this.elementRef.nativeElement.querySelector("#editorTestingRequest");
      if (editorTestingRequestRef != null) {
        //set schema for editors
        this.editorTestingRequest.setSchema(
          getSchema(this.mapping.targetAPI, this.mapping.direction, true)
        );
        this.testingModel.request = template;
      }
      console.log("New test template:", template);
    });
  }

  async onTestTransformation() {
    let testProcessingContext = await this.mappingService.testResult(
      this.mapping,
      false
    );
    this.testingModel.results = testProcessingContext.requests;
    if (testProcessingContext.errors.length > 0) {
      this.alertService.warning("Test tranformation was not successful!");
      testProcessingContext.errors.forEach((msg) => {
        this.alertService.danger(msg);
      });
    } else {
      this.alertService.success("Testing tranformation was successful!");
    }
    this.onNextTestResult();
  }

  async onSendTest() {
    let testProcessingContext = await this.mappingService.testResult(
      this.mapping,
      true
    );
    this.testingModel.results = testProcessingContext.requests;
    if (testProcessingContext.errors.length > 0) {
      this.alertService.warning("Test tranformation was not successful!");
      testProcessingContext.errors.forEach((msg) => {
        this.alertService.danger(msg);
      });
    } else {
      this.alertService.info(
        `Sending tranformation was successful: ${testProcessingContext.requests[0].response.id}`
      );
      //console.log("RES", testProcessingContext.requests[0].response);
    }
    this.onNextTestResult();
  }

  public onNextTestResult() {
    if (
      this.testingModel.selectedResult >=
      this.testingModel.results.length - 1
    ) {
      this.testingModel.selectedResult = -1;
    }
    this.testingModel.selectedResult++;
    this.selectedResult$.next(this.testingModel.selectedResult);
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
          true
        )
      );
      this.testingModel.errorMsg =
        this.testingModel.results[this.testingModel.selectedResult].error;
    } else {
      this.testingModel.request = JSON.parse("{}");
      this.testingModel.response = JSON.parse("{}");
      this.testingModel.errorMsg = undefined;
    }
  }
}
