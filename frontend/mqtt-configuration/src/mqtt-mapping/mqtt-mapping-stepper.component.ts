import { Component, EventEmitter, Input, OnInit, Output, ViewChild, ViewEncapsulation } from '@angular/core';
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

  APIs = APIs;
  QOSs = QOSs;
  SAMPLE_TEMPLATES = SAMPLE_TEMPLATES;
  TOPIC_WILDCARD = "#"

  isSubstitutionValid: boolean;
  substitutions: string = '';

  pathSource: string = '';
  pathTarget: string = '';
  dataSource: any;
  dataTarget: any;
  pathSourceMissing: boolean;
  pathTargetMissing: boolean;

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

  value: string;

  counterShowSubstitutions: number = 0;

  propertyForm: FormGroup;
  templateForm: FormGroup;
  testForm: FormGroup;

  topicUnique: boolean;
  wildcardTopic: boolean;

  TOPIC_JSON_PATH = "TOPIC";
  dataTesting: string;

  constructor(
    public mqttMappingService: MQTTMappingService,
    public alertService: AlertService,
  ) { }

  ngOnInit() {
    //console.log("Mapping to be updated:", this.mapping);
    this.initPropertyForm();
    this.initTemplateForm();
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
      topic: new FormControl(this.isUpdateExistingMapping() ? this.mapping.topic : '', Validators.required),
      targetAPI: new FormControl(this.isUpdateExistingMapping() ? this.mapping.targetAPI : '', Validators.required),
      active: new FormControl(this.isUpdateExistingMapping() ? this.mapping.active : '', Validators.required),
      createNoExistingDevice: new FormControl(this.isUpdateExistingMapping() ? this.mapping.createNoExistingDevice : '', Validators.required),
      qos: new FormControl(this.isUpdateExistingMapping() ? this.mapping.qos : '', Validators.required),
    });

    this.onChangesProperty();
  }

  private initTemplateForm(): void {
    this.templateForm = new FormGroup({
      // source: new FormControl(this.mapping.source, Validators.required),
      // target: new FormControl(this.mapping.target, Validators.required),
    });

  }

  private onChangesProperty(): void {
    this.propertyForm.get('topic').valueChanges.subscribe(val => {
      //console.log( `topic ${val}`);
    });
    this.propertyForm.get('targetAPI').valueChanges.subscribe(val => {
      //console.log( `targetAPI ${val}`);
    });
  }

  isWildcardTopic(): boolean {
    //let topic : string = e.target.value;
    const topic = this.propertyForm.get('topic').value;
    const result = topic.endsWith(this.TOPIC_WILDCARD);
    this.wildcardTopic = result;
    return result;
  }

  public checkTopicUnique(e): boolean {
    let topic = e.target.value;
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

  async onCommitButtonClicked() {
    let changed_mapping: MQTTMapping = {
      id: this.mapping.id,
      topic: this.normalizeTopic(this.propertyForm.get('topic').value),
      targetAPI: this.propertyForm.get('targetAPI').value,
      source: this.mapping.source,
      target: this.mapping.target,
      active: this.propertyForm.get('active').value,
      tested: this.mapping.tested || false,
      createNoExistingDevice: this.propertyForm.get('createNoExistingDevice').value || false,
      qos: this.propertyForm.get('qos').value,
      substitutions: this.mapping.substitutions,
      lastUpdate: Date.now(),
    }
    this.onCommit.emit(changed_mapping);
  }

  private normalizeTopic(topic: string) {
    let nt = topic.trim().replace(/\/+$/, '').replace(/^\/+/, '')
    console.log("Topic test", topic, nt);
    // append trailing slash if last character is not wildcard #
    nt = nt.concat(nt.endsWith(this.TOPIC_WILDCARD) ? '' : '/')
    return nt
  }


  getVariableNames(): string {
    const p = this.mapping.target;
    // variable name:$1, $2, $3
    //const v = p.match(/\$\d/g)||[];
    // variable name:${wert}, ${time}, ${type}
    const v = p.match(/\$\{(\w+)\}/g) || [];

    //console.log("Variable:", v, p)
    return v.join();
  }


  public isUpdateExistingMapping(): boolean {
    return !!this.mapping;
  }

  async onTestTransformationClicked() {
    let test_mapping: MQTTMapping = {
      id: this.mapping.id,
      topic: this.normalizeTopic(this.propertyForm.get('topic').value),
      targetAPI: this.propertyForm.get('targetAPI').value,
      source: this.editorSource.getText(),
      target: this.editorTarget.getText(),
      active: this.propertyForm.get('active').value,
      tested: this.mapping.tested || false,
      createNoExistingDevice: this.propertyForm.get('createNoExistingDevice').value || false,
      qos: this.propertyForm.get('qos').value,
      substitutions: this.mapping.substitutions,
      lastUpdate: Date.now(),
    }
    let dataTesting = await this.mqttMappingService.testResult(test_mapping, false);
    this.dataTesting = dataTesting;
  }

  async onSendTestClicked() {
    let test_mapping: MQTTMapping = {
      id: this.mapping.id,
      topic: this.normalizeTopic(this.propertyForm.get('topic').value),
      targetAPI: this.propertyForm.get('targetAPI').value,
      source: this.editorSource.getText(),
      target: this.editorTarget.getText(),
      active: this.propertyForm.get('active').value,
      tested: this.mapping.tested || false,
      createNoExistingDevice: this.propertyForm.get('createNoExistingDevice').value || false,
      qos: this.propertyForm.get('qos').value,
      substitutions: this.mapping.substitutions,
      lastUpdate: Date.now(),
    }
    let { data, res } = await this.mqttMappingService.sendTestResult(test_mapping);
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
    let current_target_api = this.propertyForm.get('targetAPI').value;
    this.dataTarget = JSON.parse(SAMPLE_TEMPLATES[current_target_api]);
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
        this.mapping.substitutions.push({pathSource: this.TOPIC_JSON_PATH, pathTarget: "source.id"})
      }
      this.mapping.substitutions.forEach( s => {
        //console.log ("New mapping:", s.pathSource, s.pathTarget);
        this.substitutions = this.substitutions + `[ ${s.pathSource} -> ${s.pathTarget}]`;
      } )

      this.dataSource = JSON.parse(this.mapping.source);
      //add dummy field "TOPIC" to use for mapping the device identifier form the topic ending
      if (this.isWildcardTopic()) {
        this.dataSource = {
          ...this.dataSource,
          TOPIC: "909090"
        }
      }
      this.editorTarget.setSchema(getSchema (targetAPI), null);
      this.dataTarget = JSON.parse(this.mapping.target);
      if ( !this.editMode ){
        this.dataTarget = JSON.parse(SAMPLE_TEMPLATES[targetAPI]);
        console.log("Sample template",this.dataTarget, getSchema (targetAPI))
      }
    } else if (event.step.label == "Define templates") {
      //console.log("Templates target from editor:", this.dataTarget)
      //remove dummy field "TOPIC", since it should not be stored
      let dts = JSON.parse(this.editorSource.getText())
      delete dts.TOPIC;
      this.mapping.source = JSON.stringify(dts);
      this.mapping.target = this.editorTarget.getText();
      console.log("Templates source from editor:", this.dataSource, this.editorSource.getText(), this.mapping)
      this.dataTesting = JSON.parse(this.editorSource.getText());
    } else if (event.step.label == "Test mapping") {

    }

    event.stepper.next();
  }

  public onAddSubstitutionsClicked(){
    if (this.pathSource != '' && this.pathTarget != '') {
      this.pathSourceMissing = false;
      this.pathTargetMissing = false;
      let sub: MQTTMappingSubstitution  = {
        pathSource: this.pathSource,
        pathTarget: this.pathTarget
      }
      this.mapping.substitutions.push(sub);
      this.substitutions = this.substitutions + `[ ${sub.pathSource} -> ${sub.pathTarget}]`;
      console.log ("New substitution", sub);
    } else {
      this.pathSourceMissing = this.pathSource != '' ? false: true;
      this.pathTargetMissing = this.pathTarget != '' ? false: true;
    }
  }

  public onClearSubstitutionsClicked(){
    this.mapping.substitutions = [];
    this.substitutions = "";
    if (this.mapping.substitutions.length == 0 && this.isWildcardTopic()) {
      let sub: MQTTMappingSubstitution  = {
        pathSource: this.TOPIC_JSON_PATH,
        pathTarget: "source.id"
      }
      this.mapping.substitutions.push(sub);
      this.substitutions = this.substitutions + `[ ${sub.pathSource} -> ${sub.pathTarget}]`;
    }
    console.log ("Cleared substitutions!");
  }

  public onShowSubstitutionsClicked(){
    if ( this.counterShowSubstitutions < this.mapping.substitutions.length) {
      this.setSelectionToPath(this.editorSource, this.mapping.substitutions[this.counterShowSubstitutions].pathSource)
      this.setSelectionToPath(this.editorTarget, this.mapping.substitutions[this.counterShowSubstitutions].pathTarget)
      this.counterShowSubstitutions = this.counterShowSubstitutions + 1;
    }

    if ( this.counterShowSubstitutions >= this.mapping.substitutions.length) {
      this.counterShowSubstitutions = 0;
    }
    console.log ("Show substitutions!");
  }


  setSelectionToPath(editor: JsonEditorComponent, path: string) {
    console.log("Set selection to path:", path);
    const ns = path.split(".");
    const selection = {path: ns};
    editor.setSelection(selection, selection)
  }

  jsonPathChanged(event: any) {
    let p: string = event.target.value;
    console.log(p);
    const ns = p.split(".");
    const selection = {path: ns};
    //this.editor.setSelection(selection, selection)
  }

}
