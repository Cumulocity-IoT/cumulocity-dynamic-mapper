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
import { CdkStep } from '@angular/cdk/stepper';
import { AfterContentChecked, Component, ElementRef, EventEmitter, Input, OnInit, Output, ViewChild, ViewEncapsulation } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { AlertService, C8yStepper } from '@c8y/ngx-components';
import * as _ from 'lodash';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { BehaviorSubject } from 'rxjs';
import { debounceTime } from "rxjs/operators";
import { API, Direction, Extension, Mapping, MappingSubstitution, QOS, RepairStrategy, SnoopStatus, ValidationError } from "../../shared/mapping.model";
import { checkPropertiesAreValid, checkSubstitutionIsValid, COLOR_HIGHLIGHTED, definesDeviceIdentifier, deriveTemplateTopicFromTopic, getSchema, isWildcardTopic, SAMPLE_TEMPLATES_C8Y, SCHEMA_PAYLOAD, splitTopicExcludingSeparator, TOKEN_DEVICE_TOPIC, TOKEN_TOPIC_LEVEL, whatIsIt, countDeviceIdentifiers } from "../../shared/util";
import { OverwriteSubstitutionModalComponent } from '../overwrite/overwrite-substitution-modal.component';
import { SnoopingModalComponent } from '../snooping/snooping-modal.component';
import { JsonEditorComponent, JsonEditorOptions } from '../../shared/editor/jsoneditor.component';
import { SubstitutionRendererComponent } from './substitution/substitution-renderer.component';
import { C8YRequest } from '../processor/prosessor.model';
import { MappingService } from '../core/mapping.service';
import { EditorMode, StepperConfiguration } from './stepper-model';
import { BrokerConfigurationService } from '../../mqtt-configuration/broker-configuration.service';
import { isDisabled } from './util';

@Component({
  selector: 'mapping-stepper',
  templateUrl: 'mapping-stepper.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
})

export class MappingStepperComponent implements OnInit, AfterContentChecked {

  @Input() mapping: Mapping;
  @Input() mappings: Mapping[];
  @Input() stepperConfiguration: StepperConfiguration;
  @Output() onCancel = new EventEmitter<any>();
  @Output() onCommit = new EventEmitter<Mapping>();

  API = API;
  ValidationError = ValidationError;
  RepairStrategy = RepairStrategy;
  QOS = QOS;
  SnoopStatus = SnoopStatus;
  keys = Object.keys;
  values = Object.values;
  isWildcardTopic = isWildcardTopic;
  definesDeviceIdentifier = definesDeviceIdentifier;
  isDisabled = isDisabled;
  SAMPLE_TEMPLATES_C8Y = SAMPLE_TEMPLATES_C8Y;
  COLOR_HIGHLIGHTED = COLOR_HIGHLIGHTED;
  EditorMode = EditorMode;

  propertyForm: FormGroup;
  templateForm: FormGroup;
  testForm: FormGroup;
  templateSource: any;
  templateTarget: any;
  templateTestingResults: C8YRequest[] = [];
  templateTestingErrorMsg: string;
  templateTestingRequest: any;
  templateTestingResponse: any;
  selectedTestingResult: number = -1;
  countDeviceIdentifers$: BehaviorSubject<number> = new BehaviorSubject<number>(0);

  editorOptionsSource: JsonEditorOptions = new JsonEditorOptions();
  editorOptionsTarget: JsonEditorOptions = new JsonEditorOptions();
  editorOptionsTesting: JsonEditorOptions = new JsonEditorOptions();
  sourceExpression = {
    Rresult: '',
    resultType: 'empty',
    errrorMsg: ''
  } as any
  targetExpression = {
    Rresult: '',
    resultType: 'empty',
    errrorMsg: ''
  } as any

  showConfigMapping: boolean = false;
  selectedSubstitution: number = -1;
  snoopedTemplateCounter: number = 0;
  currentSubstitution: MappingSubstitution = {
    pathSource: '',
    pathTarget: '',
    repairStrategy: RepairStrategy.DEFAULT,
    expandArray: false
  };
  step: any;

  @ViewChild('editorSource', { static: false }) editorSource: JsonEditorComponent;
  @ViewChild('editorTarget', { static: false }) editorTarget: JsonEditorComponent;
  @ViewChild('editorTestingRequest', { static: false }) editorTestingRequest: JsonEditorComponent;
  @ViewChild('editorTestingResponse', { static: false }) editorTestingResponse: JsonEditorComponent;

  @ViewChild(SubstitutionRendererComponent, { static: false }) substitutionChild: SubstitutionRendererComponent;

  @ViewChild(C8yStepper, { static: false })
  stepper: C8yStepper;
  extensions: Map<string, Extension> = new Map();
  extensionEvents$: BehaviorSubject<string[]> = new BehaviorSubject([]);
  constructor(
    public bsModalService: BsModalService,
    public mappingService: MappingService,
    public configurationService: BrokerConfigurationService,
    private alertService: AlertService,
    private elementRef: ElementRef,

  ) { }

  ngOnInit() {
    // set value for backward compatiblility
    if (!this.mapping.direction) this.mapping.direction = Direction.INCOMING;
    console.log("Mapping to be updated:", this.mapping, this.stepperConfiguration);
    let numberSnooped = (this.mapping.snoopedTemplates ? this.mapping.snoopedTemplates.length : 0);
    if (this.mapping.snoopStatus == SnoopStatus.STARTED && numberSnooped > 0) {
      this.alertService.success("Already " + numberSnooped + " templates exist. In the next step you an stop the snooping process and use the templates. Click on Next");
    }

    this.initPropertyForm();
    this.initTemplateForm();
    this.editorOptionsSource = {
      ...this.editorOptionsSource,
      modes: ['tree', 'code'],
      statusBar: false,
      navigationBar: false,
      enableSort: false,
      enableTransform: false
    };

    this.editorOptionsTarget = {
      ...this.editorOptionsTarget,
      modes: ['tree', 'code'],
      statusBar: false,
      navigationBar: false,
      enableSort: false,
      enableTransform: false,
    };

    this.editorOptionsTesting = {
      ...this.editorOptionsTesting,
      modes: ['form'],
      statusBar: false,
      navigationBar: false,
      enableSort: false,
      enableTransform: false
    };
    this.onExpressionsUpdated();
    this.countDeviceIdentifers$.next(countDeviceIdentifiers(this.mapping));

    this.extensionEvents$.subscribe(events => {
      console.log("New events from extension", events);
    })
  }

  ngAfterContentChecked(): void {
    // if json source editor is displayed then choose the first selection
    const editorSourceRef = this.elementRef.nativeElement.querySelector('#editorSource');
    if (editorSourceRef != null && !editorSourceRef.getAttribute("listener")) {
      //console.log("I'm here, ngAfterContentChecked", editorSourceRef, editorSourceRef.getAttribute("listener"));
      this.selectedSubstitution = 0;
      this.onSelectSubstitution(this.selectedSubstitution);
      editorSourceRef.setAttribute("listener", "true");
    }
  }

  private initPropertyForm(): void {
    this.propertyForm = new FormGroup({
      name: new FormControl(this.mapping.name, Validators.required),
      id: new FormControl(this.mapping.id, Validators.required),
      targetAPI: new FormControl(this.mapping.targetAPI, Validators.required),
      subscriptionTopic: new FormControl(this.mapping.subscriptionTopic, Validators.required),
      templateTopic: new FormControl(this.mapping.templateTopic, Validators.required),
      templateTopicSample: new FormControl(this.mapping.templateTopicSample, Validators.required),
      active: new FormControl(this.mapping.active),
      qos: new FormControl(this.mapping.qos, Validators.required),
      mapDeviceIdentifier: new FormControl(this.mapping.mapDeviceIdentifier),
      createNonExistingDevice: new FormControl(this.mapping.createNonExistingDevice),
      updateExistingDevice: new FormControl(this.mapping.updateExistingDevice),
      externalIdType: new FormControl(this.mapping.externalIdType),
      snoopStatus: new FormControl(this.mapping.snoopStatus),
    },
      checkPropertiesAreValid(this.mappings)
    );
  }

  private initTemplateForm(): void {
    this.templateForm = new FormGroup({
      ps: new FormControl(this.currentSubstitution.pathSource),
      pt: new FormControl(this.currentSubstitution.pathTarget),
      rs: new FormControl(this.currentSubstitution.repairStrategy),
      ea: new FormControl(this.currentSubstitution.expandArray),
      exName: new FormControl(this.mapping?.extension?.name),
      exEvent: new FormControl(this.mapping?.extension?.event),
      sourceExpressionResult: new FormControl(this.sourceExpression.result),
      targetExpressionResult: new FormControl(this.targetExpression.result),
    },
      checkSubstitutionIsValid(this.mapping, this.stepperConfiguration)
    );
  }

  public onSelectedSourcePathChanged(path: string) {
    this.updateSourceExpressionResult(path);
    this.currentSubstitution.pathSource = path;
  }

  public updateSourceExpressionResult(path: string) {
    try {
      let r: JSON = this.mappingService.evaluateExpression(this.editorSource?.get(), path);
      this.sourceExpression = {
        resultType: whatIsIt(r),
        result: JSON.stringify(r, null, 4),
        errorMsg: ''
      }
    } catch (error) {
      console.log("Error evaluating source expression: ", error);
      this.sourceExpression.errorMsg = error.message
    }
  }

  public onSelectedTargetPathChanged(path: string) {
    this.updateTargetExpressionResult(path);
    this.currentSubstitution.pathTarget = path;
  }

  public updateTargetExpressionResult(path: string) {
    try {
      let r: JSON = this.mappingService.evaluateExpression(this.editorTarget?.get(), path);
      this.targetExpression = {
        resultType: whatIsIt(r),
        result: JSON.stringify(r, null, 4),
        errorMsg: ''
      }
    } catch (error) {
      console.log("Error evaluating target expression: ", error);
      this.targetExpression.errorMsg = error.message
    }
  }

  onTopicUpdated(): void {
    this.propertyForm.get('subscriptionTopic').valueChanges.pipe(debounceTime(500))
      // distinctUntilChanged()
      .subscribe(val => {
        let touched = this.propertyForm.get('subscriptionTopic').dirty;
        console.log(`Topic changed is ${val}.`, touched);
        if (touched) {
          this.mapping.templateTopic = val as string;
        }
      });
  }

  onSubscriptionTopicChanged(event): void {
    this.mapping.templateTopic = deriveTemplateTopicFromTopic(this.mapping.subscriptionTopic);
    this.mapping.templateTopicSample = this.mapping.templateTopic;
  }

  onTemplateTopicChanged(event): void {
    this.mapping.templateTopicSample = this.mapping.templateTopic;
  }

  private onExpressionsUpdated(): void {
    this.templateForm.get('ps').valueChanges.pipe(debounceTime(500))
      // distinctUntilChanged()
      .subscribe(val => {
        //console.log(`Updated sourcePath ${val}.`, val);
        this.updateSourceExpressionResult(val);
      });

    this.templateForm.get('pt').valueChanges.pipe(debounceTime(500))
      // distinctUntilChanged()
      .subscribe(val => {
        //console.log(`Updated targetPath ${val}.`, val);
        this.updateTargetExpressionResult(val);
      });
  }

  private getCurrentMapping(patched: boolean): Mapping {
    return {
      ... this.mapping,
      source: this.reduceSourceTemplate(this.editorSource ? this.editorSource.get() : {}, patched),   //remove dummy field "_DEVICE_IDENT_", array "_TOPIC_LEVEL_" since it should not be stored
      target: this.reduceTargetTemplate(this.editorTarget.get(), patched),   //remove dummy field "_DEVICE_IDENT_", since it should not be stored
      lastUpdate: Date.now(),
    };
  }

  async onCommitButton() {
    this.onCommit.emit(this.getCurrentMapping(false));
  }

  async onTestTransformation() {
    let testProcessingContext = await this.mappingService.testResult(this.getCurrentMapping(true), false);
    this.templateTestingResults = testProcessingContext.requests;
    if (testProcessingContext.errors.length > 0) {
      this.alertService.warning("Test tranformation was not successfull!");
      testProcessingContext.errors.forEach(msg => {
        this.alertService.danger(msg);
      })
    }
    this.onNextTestResult();
  }

  async onSendTest() {
    let testProcessingContext = await this.mappingService.testResult(this.getCurrentMapping(true), true);
    this.templateTestingResults = testProcessingContext.requests;
    if (testProcessingContext.errors.length > 0) {
      this.alertService.warning("Test tranformation was not successfull!");
      testProcessingContext.errors.forEach(msg => {
        this.alertService.danger(msg);
      })
    }
    this.onNextTestResult();
  }

  public onNextTestResult() {
    if (this.selectedTestingResult >= this.templateTestingResults.length - 1) {
      this.selectedTestingResult = -1;
    }
    this.selectedTestingResult++;
    if (this.selectedTestingResult >= 0 && this.selectedTestingResult < this.templateTestingResults.length) {
      this.templateTestingRequest = this.templateTestingResults[this.selectedTestingResult].request;
      this.templateTestingResponse = this.templateTestingResults[this.selectedTestingResult].response;
      this.editorTestingRequest.setSchema(getSchema(this.templateTestingResults[this.selectedTestingResult].targetAPI, this.mapping.direction, true), null);
      this.templateTestingErrorMsg = this.templateTestingResults[this.selectedTestingResult].error
    } else {
      this.templateTestingRequest = JSON.parse("{}");
      this.templateTestingResponse = JSON.parse("{}");
      this.templateTestingErrorMsg = undefined;
    }
  }

  async onSampleButton() {
    this.templateTarget = this.expandTargetTemplate(JSON.parse(SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI]));
    this.editorTarget.set(this.templateTarget);
  }

  async onCancelButton() {
    this.onCancel.emit();
  }

  onSelectExtension(extension) {
    console.log("onSelectExtension", extension);
    this.mapping.extension.name = extension;
    this.extensionEvents$.next(Object.keys(this.extensions[extension].extensionEntries));
  }


  public async onNextStep(event: { stepper: C8yStepper; step: CdkStep }): Promise<void> {

    console.log("OnNextStep", event.step.label, this.mapping)
    this.step = event.step.label;

    if (this.step == "Define topic") {
      console.log("Populate jsonPath if wildcard:", isWildcardTopic(this.mapping.subscriptionTopic), this.mapping.substitutions.length)
      console.log("Templates from mapping:", this.mapping.target, this.mapping.source)
      this.enrichTemplates();
      // set schema for editors
      this.editorTarget.setSchema(getSchema(this.mapping.targetAPI, this.mapping.direction, true), null);
      this.editorSource.setSchema(getSchema(this.mapping.targetAPI, this.mapping.direction, false), null);
      this.editorTestingRequest.setSchema(getSchema(this.mapping.targetAPI, this.mapping.direction, true), null);
      this.editorTestingResponse.setSchema(getSchema(this.mapping.targetAPI, this.mapping.direction, true), null);
      this.extensions = await this.configurationService.getProcessorExtensions() as any;
      if (this.mapping?.extension?.name) {
        this.extensionEvents$.next(Object.keys(this.extensions[this.mapping?.extension?.name].extensionEntries));
      }

      let numberSnooped = (this.mapping.snoopedTemplates ? this.mapping.snoopedTemplates.length : 0);
      const initialState = {
        snoopStatus: this.mapping.snoopStatus,
        numberSnooped: numberSnooped,
      }
      if (this.mapping.snoopStatus == SnoopStatus.ENABLED && this.mapping.snoopedTemplates.length == 0) {
        console.log("Ready to snoop ...");
        const modalRef: BsModalRef = this.bsModalService.show(SnoopingModalComponent, { initialState });
        modalRef.content.closeSubject.subscribe((confirm: boolean) => {
          if (confirm) {
            this.onCommit.emit(this.getCurrentMapping(false));
          } else {
            this.mapping.snoopStatus = SnoopStatus.NONE
            event.stepper.next();
          }
        })
      } else if (this.mapping.snoopStatus == SnoopStatus.STARTED) {
        console.log("Continue snoop ...?");
        const modalRef: BsModalRef = this.bsModalService.show(SnoopingModalComponent, { initialState });
        modalRef.content.closeSubject.subscribe((confirm: boolean) => {
          if (confirm) {
            this.mapping.snoopStatus = SnoopStatus.STOPPED
            if (numberSnooped > 0) {
              this.templateSource = JSON.parse(this.mapping.snoopedTemplates[0]);
              let levels: String[] = splitTopicExcludingSeparator(this.mapping.templateTopicSample);
              this.templateSource = this.expandSourceTemplate(this.templateSource, levels);
              this.onSampleButton();
            }
            event.stepper.next();
          } else {
            this.onCancel.emit();
          }
        })
      } else {
        event.stepper.next();
      }
    } else if (this.step == "Define templates and substitutions") {
      this.editorTestingRequest.set(this.editorSource ? this.editorSource.get() : {} as JSON);
      this.onSelectSubstitution(0);
      event.stepper.next();
    }

  }

  private enrichTemplates() {
    let levels: String[] = splitTopicExcludingSeparator(this.mapping.templateTopicSample);
    this.templateSource = this.expandSourceTemplate(JSON.parse(this.mapping.source), levels);
    if (this.stepperConfiguration.editorMode == EditorMode.CREATE) {
      this.templateTarget = JSON.parse(SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI]);
      console.log("Sample template", this.templateTarget, getSchema(this.mapping.targetAPI, this.mapping.direction, true));
    } else {
      this.templateTarget = JSON.parse(this.mapping.target);
    }
    this.templateTarget = this.expandTargetTemplate(this.templateTarget);
  }

  async onSnoopedSourceTemplates() {
    if (this.snoopedTemplateCounter >= this.mapping.snoopedTemplates.length) {
      this.snoopedTemplateCounter = 0;
    }
    try {
      this.templateSource = JSON.parse(this.mapping.snoopedTemplates[this.snoopedTemplateCounter]);
    } catch (error) {
      this.templateSource = { message: this.mapping.snoopedTemplates[this.snoopedTemplateCounter] };
      console.warn("The payload was not in JSON format, now wrap it:", this.templateSource)
    }
    this.templateSource = this.expandSourceTemplate(this.templateSource, splitTopicExcludingSeparator(this.mapping.templateTopicSample));
    this.mapping.snoopStatus = SnoopStatus.STOPPED;
    this.snoopedTemplateCounter++;
  }

  async onTargetAPIChanged(evt) {
    this.mapping.target = SAMPLE_TEMPLATES_C8Y[this.mapping.targetAPI];
  }

  public onAddSubstitution() {
    if (this.currentSubstitution.pathSource != '' && this.currentSubstitution.pathTarget != '') {
      this.addSubstitution(this.currentSubstitution);
      this.selectedSubstitution = -1;
      console.log("New substitution", this.currentSubstitution, this.mapping.substitutions);
      this.currentSubstitution = {
        pathSource: '',
        pathTarget: '',
        repairStrategy: RepairStrategy.DEFAULT,
        expandArray: false
      };
      this.templateForm.updateValueAndValidity({ 'emitEvent': true });
    }
  }

  public onDeleteAllSubstitution() {
    this.mapping.substitutions = [];
    this.countDeviceIdentifers$.next(countDeviceIdentifiers(this.mapping));

    console.log("Cleared substitutions!");
  }

  public onDeleteSelectedSubstitution() {
    console.log("Delete selected substitution", this.selectedSubstitution);
    if (this.selectedSubstitution < this.mapping.substitutions.length) {
      this.mapping.substitutions.splice(this.selectedSubstitution, 1);
      this.selectedSubstitution = -1;
    }
    this.countDeviceIdentifers$.next(countDeviceIdentifiers(this.mapping));
    console.log("Deleted substitution", this.mapping.substitutions.length);

  }

  public onDeleteSubstitution(selected: number){
    console.log("Delete selected substitution", selected);
    if (selected < this.mapping.substitutions.length) {
      this.mapping.substitutions.splice(selected, 1);
      selected = -1;
    }
    this.countDeviceIdentifers$.next(countDeviceIdentifiers(this.mapping));
    console.log("Deleted substitution", this.mapping.substitutions.length);

  }

  private addSubstitution(st: MappingSubstitution) {
    let sub: MappingSubstitution = _.clone(st);
    let existingSubstitution = -1;
    this.mapping.substitutions.forEach((s, index) => {
      if (sub.pathTarget == s.pathTarget) {
        existingSubstitution = index;
      }
    })

    if (existingSubstitution != -1) {
      const initialState = {
        substitution: this.mapping.substitutions[existingSubstitution],
        targetAPI: this.mapping.targetAPI
      }
      const modalRef: BsModalRef = this.bsModalService.show(OverwriteSubstitutionModalComponent, { initialState });
      modalRef.content.closeSubject.subscribe(
        (overwrite: boolean) => {
          console.log("Overwriting substitution I:", overwrite, this.mapping.substitutions);
          if (overwrite) {
            // when overwritting substitution then copy deviceIdentifier property
            this.mapping.substitutions[existingSubstitution] = sub;
          }
          this.templateForm.updateValueAndValidity({ 'emitEvent': true });
          console.log("Overwriting substitution II:", overwrite, this.mapping.substitutions);
        }
      );
    } else {
      this.mapping.substitutions.push(sub);
    }
    this.countDeviceIdentifers$.next(countDeviceIdentifiers(this.mapping));

  }

  public onNextSubstitution() {
    this.substitutionChild.scrollToSubstitution(this.selectedSubstitution);
    if (this.selectedSubstitution >= this.mapping.substitutions.length - 1) {
      this.selectedSubstitution = -1;
    }
    this.selectedSubstitution++;
    this.onSelectSubstitution(this.selectedSubstitution);
  }

  public onSelectSubstitution(selected: number) {
    if (selected < this.mapping.substitutions.length && selected > -1) {
      this.selectedSubstitution = selected
      this.currentSubstitution = _.clone(this.mapping.substitutions[selected])
      this.editorSource?.setSelectionToPath(this.currentSubstitution.pathSource);
      this.editorTarget.setSelectionToPath(this.currentSubstitution.pathTarget);
    }
  }

  private expandSourceTemplate(t: object, levels: String[]): object {
    if (Array.isArray(t)) {
      return t
    } else {
      return {
        ...t,
        _TOPIC_LEVEL_: levels
      };
    }
  }

  private expandTargetTemplate(t: object): object {
    if (this.mapping.targetAPI == API.INVENTORY.name) {
      return {
        ...t,
        _DEVICE_IDENT_: "909090"
      };
    } else {
      return t;
    }
  }

  private reduceSourceTemplate(t: object, patched: boolean): string {
    if (!patched) delete t[TOKEN_TOPIC_LEVEL];
    let tt = JSON.stringify(t);
    return tt;
  }

  private reduceTargetTemplate(t: object, patched: boolean): string {
    if (!patched) delete t[TOKEN_DEVICE_TOPIC];
    let tt = JSON.stringify(t);
    return tt;
  }
}