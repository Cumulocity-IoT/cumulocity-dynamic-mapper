import { Component, ElementRef, EventEmitter, Input, OnInit, Output, ViewChild, ViewEncapsulation } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { MQTTMappingService } from './mqtt-mapping.service';
import { AlertService, C8yStepper } from '@c8y/ngx-components';
import { MQTTMapping, MQTTMappingSubstitution, SAMPLE_TEMPLATES, APIs, QOSs, SCHEMA_EVENT, SCHEMA_ALARM, SCHEMA_MEASUREMENT, getSchema, SCHEMA_PAYLOAD } from "../mqtt-configuration.model"
import { CdkStep } from '@angular/cdk/stepper';
import { JsonEditorComponent } from '@maaxgr/ang-jsoneditor';

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

  COLOR_PALETTE = ['#d5f4e6', '#80ced6', '#80ced6']
  APIs = APIs;
  QOSs = QOSs;
  SAMPLE_TEMPLATES = SAMPLE_TEMPLATES;
  TOPIC_WILDCARD = "#"

  paletteCounter:number = 0;
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

  private setSelectionSource = function (node: any, event: any) {
    if (event.type == "click") {
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
      this.pathSource = path;
      for (let item of this.selectionList) {
        console.log("Reset item:", item);
        item.setAttribute('style',null);
      }
      this.setSelectionToPath(this.editorSource, path)
      console.log("Set pathSource:", path);
    }
  }.bind(this)


  private setSelectionTarget = function (node: any, event: any) {
    if (event.type == "click") {
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
      this.pathTarget = path;
      for (let item of this.selectionList) {
        console.log("Reset item:", item);
        item.setAttribute('style', null);
      }
      this.setSelectionToPath(this.editorTarget, path)
      console.log("Set pathTarget:", path);
    }
  }.bind(this)

  editorOptionsSource: any
  editorOptionsTarget: any
  editorOptionsTesting: any

  @ViewChild('editorSource', { static: false }) editorSource!: JsonEditorComponent;
  @ViewChild('editorTarget', { static: false }) editorTarget!: JsonEditorComponent;

  @ViewChild(C8yStepper, { static: false })
  stepper: C8yStepper;

  showConfigMapping: boolean = false;

  isConnectionToMQTTEstablished: boolean;

  counterShowSubstitutions: number = 0;

  propertyForm: FormGroup;
  testForm: FormGroup;

  topicUnique: boolean;

  TOPIC_JSON_PATH = "TOPIC";

  constructor(
    public mqttMappingService: MQTTMappingService,
    public alertService: AlertService,
    private elementRef: ElementRef
  ) { }

  ngOnInit() {
    //console.log("Mapping to be updated:", this.mapping);
    console.log ("ElementRef:", this.elementRef.nativeElement);
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

  }

  private initPropertyForm(): void {
    this.propertyForm = new FormGroup({
      topic: new FormControl(this.mapping.topic, Validators.required),
      targetAPI: new FormControl(this.mapping.targetAPI, Validators.required),
      active: new FormControl(this.mapping.active, Validators.required),
      createNoExistingDevice: new FormControl(this.mapping.createNoExistingDevice, Validators.required),
      qos: new FormControl(this.mapping.qos, Validators.required),
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
      target: this.editorTarget.getText(),
      active: this.propertyForm.get('active').value,
      tested: this.mapping.tested || false,
      createNoExistingDevice: this.propertyForm.get('createNoExistingDevice').value || false,
      qos: this.propertyForm.get('qos').value,
      substitutions: this.mapping.substitutions,
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

      this.templateSource = JSON.parse(this.mapping.source);
      //add dummy field "TOPIC" to use for mapping the device identifier form the topic ending
      if (this.isWildcardTopic()) {
        this.templateSource = {
          ...this.templateSource,
          TOPIC: "909090"
        }
      }
      this.editorTarget.setSchema(getSchema(targetAPI), null);
      this.templateTarget = JSON.parse(this.mapping.target);
      if (!this.editMode) {
        this.templateTarget = JSON.parse(SAMPLE_TEMPLATES[targetAPI]);
        console.log("Sample template", this.templateTarget, getSchema(targetAPI))
      }
    } else if (event.step.label == "Define templates") {
      console.log("Templates source from editor:", this.templateSource, this.editorSource.getText(), this.getCurrentMapping())
      this.dataTesting = this.editorSource.get();
    } else if (event.step.label == "Test mapping") {

    }
    event.stepper.next();

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
      this.selectionList  = this.elementRef.nativeElement.querySelectorAll('.jsoneditor-selected');
      for (let item of this.selectionList) {
        item.setAttribute('style', `background: ${nextColor};`);
      }
      this.counterShowSubstitutions = this.counterShowSubstitutions + 1;
    }

    if (this.counterShowSubstitutions >= this.mapping.substitutions.length) {
      this.counterShowSubstitutions = 0;
    }
    console.log("Show substitutions!");
  }

  private addSubstitution(sub: MQTTMappingSubstitution) {
    this.mapping.substitutions.push(sub);
    this.substitutions = this.substitutions + `[ ${sub.pathSource} -> ${sub.pathTarget}]`;
  }

}
