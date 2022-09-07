import { CdkStep } from '@angular/cdk/stepper';
import { Component, ElementRef, EventEmitter, Input, OnInit, Output, ViewChild, ViewEncapsulation } from '@angular/core';
import { FormBuilder, FormControl, FormGroup, Validators } from '@angular/forms';
import { AlertService, C8yStepper } from '@c8y/ngx-components';
import { JsonEditorComponent } from '@maaxgr/ang-jsoneditor';
import { APIs, getSchema, MQTTMapping, MQTTMappingSubstitution, QOSs, SAMPLE_TEMPLATES, SCHEMA_PAYLOAD, Snoop_Status } from "../mqtt-configuration.model";
import { MQTTMappingService } from './mqtt-mapping.service';
import { JSONPath } from 'jsonpath-plus';
import { search } from '@metrichor/jmespath';

@Component({
  selector: 'mqtt-mapping-stepper',
  templateUrl: 'mqtt-mapping-stepper.component.html',
  styleUrls: ['./mqtt-mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
})

export class MQTTMappingStepperComponent implements OnInit {

  @Input() mapping: MQTTMapping;
  @Input() mappings: MQTTMapping[];
  @Input() editMode: boolean;
  @Output() onCancel = new EventEmitter<any>();
  @Output() onCommit = new EventEmitter<MQTTMapping>();

  COLOR_PALETTE = ['#d5f4e6', '#80ced6', '#fefbd8', '#618685', '#ffef96', '#50394c', '#b2b2b2', '#f4e1d2']
  APIs = APIs;
  QOSs = QOSs;
  Snoop_Status = Snoop_Status;
  keys = Object.keys;
  SAMPLE_TEMPLATES = SAMPLE_TEMPLATES;
  TOPIC_WILDCARD = "#"
  JSONATA = require("jsonata");

  paletteCounter: number = 0;
  snoopedTemplateCounter: number = 0;
  isSubstitutionValid: boolean;
  substitutions: string = '';

  pathSource: string = '';
  pathTarget: string = '';
  templateSource: any;
  templateTarget: any;
  dataTesting: any;
  pathSourceMissing: boolean;
  pathTargetMissing: boolean;
  selectionList: any = [];
  
  clicksTarget: []
  clicksSource: []
  editorOptionsSource: any
  editorOptionsTarget: any
  editorOptionsTesting: any
  sourceExpression: string
  sourceExpressionResult: string
  sourceExpressionErrorMsg: string ='';

  private setSelectionSource = function (node: any, event: any) {
    if (event.type == "click") {
      if (this.clicksSource == undefined) this.clicksSource = [];
      this.clicksSource.push(Date.now());
      this.clicksSource = this.clicksSource.slice(-2);
      let doubleClick = (this.clicksSource.length > 1 ? this.clicksSource[1] - this.clicksSource[0] : Infinity);
      //console.log("Set target editor event:", event.type, this.clicksTarget, doubleClick);
      var path = "";
      for (let i = 0; i < node.path.length; i++) {
        if (typeof node.path[i] === 'number') {
          path = path.substring(0, path.length - 1);
          path += '[' + node.path[i] + ']';

        } else {
          path += node.path[i];
        }
        if (i !== node.path.length - 1) path += ".";
      }
      for (let item of this.selectionList) {
        //console.log("Reset item:", item);
        item.setAttribute('style', null);
      }
      // test if doubleclicked
      if (doubleClick < 750) {
        this.setSelectionToPath(this.editorSource, path)
        this.pathSource = path;
        this.sourceExpression = path;
      }
      //console.log("Set pathSource:", path);
    }
  }.bind(this)


  private setSelectionTarget = function (node: any, event: any) {
    if (event.type == "click") {
      if (this.clicksTarget == undefined) this.clicksTarget = [];
      this.clicksTarget.push(Date.now());
      this.clicksTarget = this.clicksTarget.slice(-2);
      let doubleClick = (this.clicksTarget.length > 1 ? this.clicksTarget[1] - this.clicksTarget[0] : Infinity);
      //console.log("Set target editor event:", event.type, this.clicksTarget, doubleClick);

      var path = "";
      for (let i = 0; i < node.path.length; i++) {
        if (typeof node.path[i] === 'number') {
          path = path.substring(0, path.length - 1);
          path += '[' + node.path[i] + ']';

        } else {
          path += node.path[i];
        }
        if (i !== node.path.length - 1) path += ".";
      }
      for (let item of this.selectionList) {
        //console.log("Reset item:", item);
        item.setAttribute('style', null);
      }
      // test if doubleclicked
      if (doubleClick < 750) {
        this.setSelectionToPath(this.editorTarget, path)
        this.pathTarget = path;
      }
      //console.log("Set pathTarget:", path);
    }
  }.bind(this)


  @ViewChild('editorSource', { static: false }) editorSource!: JsonEditorComponent;
  @ViewChild('editorTarget', { static: false }) editorTarget!: JsonEditorComponent;

  @ViewChild(C8yStepper, { static: false })
  stepper: C8yStepper;

  showConfigMapping: boolean = false;

  isConnectionToMQTTEstablished: boolean;

  counterShowSubstitutions: number = 0;

  propertyForm: FormGroup;
  testForm: FormGroup;

  topicUnique: boolean = true;

  TOPIC_JSON_PATH = "TOPIC";

  constructor(
    public mqttMappingService: MQTTMappingService,
    public alertService: AlertService,
    private elementRef: ElementRef,
    private fb: FormBuilder,
  ) { }

  ngOnInit() {
    //console.log("Mapping to be updated:", this.mapping);
    //console.log ("ElementRef:", this.elementRef.nativeElement);
    this.initPropertyForm();
    this.editorOptionsSource = {
      modes: ['tree', 'code'],
      statusBar: false,
      navigationBar: false,
      enableSort: false,
      enableTransform: false,
      enableSearch: false,
      onEvent: this.setSelectionSource,
      schema: SCHEMA_PAYLOAD
    };

    this.editorOptionsTarget = {
      modes: ['tree', 'code'],
      statusBar: false,
      navigationBar: false,
      enableSort: false,
      enableTransform: false,
      enableSearch: false,
      onEvent: this.setSelectionTarget,
      schema: getSchema(this.mapping.targetAPI)
    };

    this.editorOptionsTesting = {
      modes: ['form'],
      statusBar: false,
      navigationBar: false,
      enableSort: false,
      enableTransform: false,
      enableSearch: false,
      onEvent: this.setSelectionSource,
      schema: SCHEMA_PAYLOAD
    };

    this.initTemplateEditors();

  }

  private initPropertyForm(): void {
    this.propertyForm = this.fb.group({
      topic: new FormControl(this.mapping.topic, Validators.required),
      targetAPI: new FormControl(this.mapping.targetAPI, Validators.required),
      active: [this.mapping.active],
      createNoExistingDevice: new FormControl(this.mapping.createNoExistingDevice, Validators.required),
      qos: new FormControl(this.mapping.qos, Validators.required),
      mapDeviceIdentifier: new FormControl(this.mapping.mapDeviceIdentifier),
      externalIdType: new FormControl(this.mapping.externalIdType),
      snoopTemplates: new FormControl(this.mapping.snoopTemplates),
    });

  }

  private setSelectionToPath(editor: JsonEditorComponent, path: string) {
    console.log("Set selection to path:", path);
    const ns = path.split(".");
    const selection = { path: ns };
    editor.setSelection(selection, selection)
  }

  private isWildcardTopic(): boolean {
    const topic = this.propertyForm.get('topic').value;
    const result = topic.endsWith(this.TOPIC_WILDCARD);
    return result;
  }

  public sourceExpressionChanged(evt){
    let path = evt.target.value;
    console.log("Evaluate expression:", path, this.editorSource.get());

    // JSONPath library
    //this.sourceExpressionResult = JSON.stringify(JSONPath({path: path, json: this.editorSource.get()}), null, 4)
    // JMES library
    //this.sourceExpressionResult = JSON.stringify(search(this.editorSource.get() as any, path), null, 4)
    // JSONATA
    var expression = this.JSONATA(path)
    try {
      this.sourceExpressionResult = JSON.stringify(expression.evaluate(this.editorSource.get()), null, 4)
      this.sourceExpressionErrorMsg = '';
    } catch (error) {
      console.log("Error evaluating expression: ", error);
      this.sourceExpressionErrorMsg = error.message
    }
  }

  checkTopicIsUnique(evt): boolean {
    let topic = evt.target.value;
    console.log("Changed topic: ", topic);
    let result = true;
    result = this.mappings.every(m => {
      if (topic == m.topic && this.mapping.id != m.id) {
        return false;
      } else {
        return true;
      }
    })
    console.log("Check if topic is unique: ", this.mapping, topic, result, this.mappings);
    this.topicUnique = result;
    // invalidate fields, since entry is not valid
    if (!result) this.propertyForm.controls['topic'].setErrors({ 'incorrect': true });
    return result;
  }

  private normalizeTopic(topic: string) {
    let nt = topic.trim().replace(/\/+$/, '').replace(/^\/+/, '')
    console.log("Topic test", topic, nt);
    // append trailing slash if last character is not wildcard #
    nt = nt.concat(nt.endsWith(this.TOPIC_WILDCARD) ? '' : '/')
    return nt
  }

  private getCurrentMapping(): MQTTMapping {
    //remove dummy field "TOPIC", since it should not be stored
    let dts = this.editorSource.get()
    delete dts['TOPIC'];
    let st = JSON.stringify(dts);

    return {
      id: this.mapping.id,
      topic: this.normalizeTopic(this.propertyForm.get('topic').value),
      targetAPI: this.propertyForm.get('targetAPI').value,
      source: st,
      target: JSON.stringify(this.editorTarget.get()),
      active: this.propertyForm.get('active').value,
      tested: this.mapping.tested || false,
      createNoExistingDevice: this.propertyForm.get('createNoExistingDevice').value || false,
      qos: this.propertyForm.get('qos').value,
      substitutions: this.mapping.substitutions,
      mapDeviceIdentifier: this.propertyForm.get('mapDeviceIdentifier').value,
      externalIdType: this.propertyForm.get('externalIdType').value,
      snoopTemplates: this.propertyForm.get('snoopTemplates').value,
      snoopedTemplates: this.mapping.snoopedTemplates,
      lastUpdate: Date.now(),
    };
  }

  async onCommitButtonClicked() {
    this.onCommit.emit(this.getCurrentMapping());
  }

  async onTestTransformationClicked() {
    let dataTesting = await this.mqttMappingService.testResult(this.getCurrentMapping(), false);
    this.dataTesting = dataTesting;
  }

  async onSendTestClicked() {
    let { data, res } = await this.mqttMappingService.sendTestResult(this.getCurrentMapping());
    //console.log ("My data:", data );
    if (res.status == 200 || res.status == 201) {
      this.alertService.success("Successfully tested mapping!");
      this.mapping.tested = true;
      this.dataTesting = data as any;
    } else {
      let error = await res.text();
      this.alertService.danger("Failed to tested mapping: " + error);
    }
  }

  async onSampleButtonClicked() {
    this.templateTarget = JSON.parse(SAMPLE_TEMPLATES[this.propertyForm.get('targetAPI').value]);
  }

  async onCancelButtonClicked() {
    this.onCancel.emit();
  }

  public onNextSelected(event: { stepper: C8yStepper; step: CdkStep }): void {
    const targetAPI = this.propertyForm.get('targetAPI').value
    console.log("OnNextSelected", event.step.label, targetAPI, this.editMode)

    if (event.step.label == "Define topic") {
      this.substitutions = '';
      console.log("Populate jsonPath if wildcard:", this.isWildcardTopic(), this.mapping.substitutions.length)
      console.log("Templates from mapping:", this.mapping.target, this.mapping.source)
      if (this.mapping.substitutions.length == 0 && this.isWildcardTopic()) {
        this.mapping.substitutions.push({ pathSource: this.TOPIC_JSON_PATH, pathTarget: "source.id" })
      }
      this.mapping.substitutions.forEach(s => {
        //console.log ("New mapping:", s.pathSource, s.pathTarget);
        this.substitutions = this.substitutions + `[ ${s.pathSource} -> ${s.pathTarget}]`;
      })

      this.initTemplateEditors();
      this.editorTarget.setSchema(getSchema(targetAPI), null);

    } else if (event.step.label == "Define templates") {
      console.log("Templates source from editor:", this.templateSource, this.editorSource.getText(), this.getCurrentMapping())
      this.dataTesting = this.editorSource.get();
    } else if (event.step.label == "Test mapping") {

    }
    if (this.propertyForm.get('snoopTemplates').value == Snoop_Status.ENABLED && this.mapping.snoopedTemplates.length == 0) {
      console.log("Ready to snoop ...");
      this.onCommit.emit(this.getCurrentMapping());
    } else {
      event.stepper.next();
    }

  }

  private initTemplateEditors() {
    const targetAPI = this.propertyForm.get('targetAPI').value
    this.templateSource = JSON.parse(this.mapping.source);
    //add dummy field "TOPIC" to use for mapping the device identifier form the topic ending
    if (this.isWildcardTopic()) {
      this.templateSource = {
        ...this.templateSource,
        TOPIC: "909090"
      };
    }
    this.templateTarget = JSON.parse(this.mapping.target);
    if (!this.editMode) {
      this.templateTarget = JSON.parse(SAMPLE_TEMPLATES[targetAPI]);
      console.log("Sample template", this.templateTarget, getSchema(targetAPI));
    }
  }

  async onSnoopedSourceTemplatesClicked() {
    if (this.snoopedTemplateCounter >= this.mapping.snoopedTemplates.length) {
      this.snoopedTemplateCounter = 0;
    }
    this.templateSource = JSON.parse(this.mapping.snoopedTemplates[this.snoopedTemplateCounter]);
    //add dummy field "TOPIC" to use for mapping the device identifier form the topic ending
    if (this.isWildcardTopic()) {
      this.templateSource = {
        ...this.templateSource,
        TOPIC: "909090"
      };
    }
    // disable further snooping for this template
    this.propertyForm.patchValue({ "snoopTemplates": Snoop_Status.STOPPED });
    this.snoopedTemplateCounter++;
  }

  public onAddSubstitutionsClicked() {
    this.pathSourceMissing = this.pathSource != '' ? false : true;
    this.pathTargetMissing = this.pathTarget != '' ? false : true;

    if (!this.pathSourceMissing && !this.pathTargetMissing) {
      let sub: MQTTMappingSubstitution = {
        pathSource: this.pathSource,
        pathTarget: this.pathTarget
      }
      this.addSubstitution(sub);
      console.log("New substitution", sub);
      this.pathSource = '';
      this.pathTarget = '';
      this.pathSourceMissing = true;
      this.pathTargetMissing = true;
    }
  }

  public onClearSubstitutionsClicked() {
    this.mapping.substitutions = [];
    this.substitutions = "";
    if (this.mapping.substitutions.length == 0 && this.isWildcardTopic()) {
      let sub: MQTTMappingSubstitution = {
        pathSource: this.TOPIC_JSON_PATH,
        pathTarget: "source.id"
      }
      this.addSubstitution(sub);
    }
    console.log("Cleared substitutions!");
  }

  public onShowSubstitutionsClicked() {
    let nextColor = this.COLOR_PALETTE[this.paletteCounter];
    this.paletteCounter++;
    if (this.paletteCounter >= this.COLOR_PALETTE.length) {
      this.paletteCounter = 0;
    }
    if (this.counterShowSubstitutions < this.mapping.substitutions.length) {
      // reset background color of old selection list
      for (let item of this.selectionList) {
        item.setAttribute('style', null);
      }

      this.setSelectionToPath(this.editorSource, this.mapping.substitutions[this.counterShowSubstitutions].pathSource)
      this.setSelectionToPath(this.editorTarget, this.mapping.substitutions[this.counterShowSubstitutions].pathTarget)
      console.log("Found querySelectorAll elements:", this.elementRef.nativeElement.querySelectorAll('.jsoneditor-selected'))
      //this.selectionList  = this.elementRef.nativeElement.getElementsByClassName('jsoneditor-selected');
      this.selectionList = this.elementRef.nativeElement.querySelectorAll('.jsoneditor-selected');
      for (let item of this.selectionList) {
        item.setAttribute('style', `background: ${nextColor};`);
      }
      this.counterShowSubstitutions++;
    }

    if (this.counterShowSubstitutions >= this.mapping.substitutions.length) {
      this.counterShowSubstitutions = 0;
      this.paletteCounter = 0;
    }
    console.log("Show substitutions!");
  }

  private addSubstitution(sub: MQTTMappingSubstitution) {
    this.mapping.substitutions.push(sub);
    this.substitutions = this.substitutions + `[ ${sub.pathSource} -> ${sub.pathTarget}] `;
  }

}
