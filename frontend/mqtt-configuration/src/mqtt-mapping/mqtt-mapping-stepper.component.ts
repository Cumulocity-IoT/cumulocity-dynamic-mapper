import { Component, EventEmitter, Inject, Input, OnInit, Output, QueryList, ViewChild, ViewChildren, ViewEncapsulation } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { MQTTMappingService } from './mqtt-mapping.service';
import { AlertService, C8yStepper } from '@c8y/ngx-components';
import { MQTTMapping, MQTTMappingSubstitution, SAMPLE_TEMPLATES, APIs, QOSs, SCHEMA_EVENT, SCHEMA_ALARM, SCHEMA_MEASUREMENT } from "../mqtt-configuration.model"
import { JSONPath } from 'jsonpath-plus';
import { CdkStep } from '@angular/cdk/stepper';
import { JsonEditorComponent, JsonEditorOptions } from '@maaxgr/ang-jsoneditor';

@Component({
  selector: 'mqtt-mapping-stepper',
  templateUrl: 'mqtt-mapping-stepper.component.html',
  styleUrls: ['./mqtt-mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
})

export class MQTTMappingStepperComponent implements OnInit {

  @Input() mapping: MQTTMapping;
  @Input() mappings: MQTTMapping[];
  @Output() onCancel = new EventEmitter<any>();
  @Output() onCommit = new EventEmitter<MQTTMapping>();

  APIs = APIs;
  QOSs = QOSs;
  SAMPLE_TEMPLATES = SAMPLE_TEMPLATES;
  TOPIC_WILDCARD = "#"

  isSubstitutionValid: boolean;

  pathSource: string;
  pathTarget: string;
  dataSource: any;
  dataTarget: any;

  editorOptionsSource: any = {
    modes: ['code', 'tree'],
    statusBar: false,
    enableSort: false,
    enableTransform: false,
    enableSearch: false,
    onEvent: function (node: any, event: any) {
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
        console.log("Path:", path);
      }
    },
    schema: SCHEMA_EVENT
  };

  editorOptionsTarget: any = {
    modes: ['code', 'tree'],
    statusBar: false,
    enableSort: false,
    enableTransform: false,
    enableSearch: false,
    onEvent: function (node: any, event: any) {
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
        console.log("Path:", path);
      }
    },
    schema: SCHEMA_EVENT
  };


  @ViewChild('editorSource', { static: false }) editorSource!: JsonEditorComponent;
  @ViewChild('editorTarget', { static: false }) editorTarget!: JsonEditorComponent;

  @ViewChild(C8yStepper, { static: false })
  stepper: C8yStepper;

  showConfigMapping: boolean = false;

  isConnectionToMQTTEstablished: boolean;

  value: string;

  propertiesForm: FormGroup;
  templateForm: FormGroup;
  jsonPathForm: FormGroup;
  testForm: FormGroup;

  topicUnique: boolean;
  wildcardTopic: boolean;

  TOPIC_JSON_PATH = "$.TOPIC";

  constructor(
    public mqttMappingService: MQTTMappingService,
    public alertService: AlertService,
  ) { }

  ngOnInit() {
    //console.log("Mapping to be updated:", this.mapping);
    this.initForm();
  }

  private initForm(): void {
    this.propertiesForm = new FormGroup({
      topic: new FormControl(this.isUpdateExistingMapping() ? this.mapping.topic : '', Validators.required),
      targetAPI: new FormControl(this.isUpdateExistingMapping() ? this.mapping.targetAPI : '', Validators.required),
      active: new FormControl(this.isUpdateExistingMapping() ? this.mapping.active : '', Validators.required),
      createNoExistingDevice: new FormControl(this.isUpdateExistingMapping() ? this.mapping.createNoExistingDevice : '', Validators.required),
      qos: new FormControl(this.isUpdateExistingMapping() ? this.mapping.qos : '', Validators.required),
    });

    this.templateForm = new FormGroup({
      source: new FormControl(this.mapping.source, Validators.required),
      target: new FormControl(this.mapping.target, Validators.required),
    });

    this.jsonPathForm = new FormGroup({
      source: new FormControl(this.mapping.source),
      jsonPath: new FormControl(''),
      jsonPathResult: new FormControl(''),
      variableNames: new FormControl(this.getVariableNames(), Validators.required),
      variableJsonPathes: new FormControl(this.getVariableJsonPathes(), Validators.required),
    });

    this.testForm = new FormGroup({
      testResult: new FormControl(''),
    });
  }


  onJsonPathChanged() {
    const p = this.jsonPathForm.get('jsonPath').value;
    const d = JSON.parse(this.templateForm.get('source').value);
    const r = JSONPath({ path: p, json: d, wrap: false });
    console.log("Changed jsonPath: ", p, d, r);
    this.jsonPathForm.patchValue({
      jsonPathResult: r,
    });
  }

  appendJsonPath(): void {
    const p = this.jsonPathForm.get('jsonPath').value;
    let ps = this.jsonPathForm.get('variableJsonPathes').value;
    if (ps != '') {
      ps = ps.concat(", ").concat(p);
    } else {
      ps = ps.concat(p);
    }
    this.jsonPathForm.patchValue({ variableJsonPathes: ps })
  }

  isWildcardTopic(): boolean {
    //let topic : string = e.target.value;
    const topic = this.propertiesForm.get('topic').value;
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
    if (!result) this.propertiesForm.controls['topic'].setErrors({ 'incorrect': true });
    return result;
  }

  async onCommitButtonClicked() {
    let changed_mapping: MQTTMapping = {
      id: this.mapping.id,
      topic: this.normalizeTopic(this.propertiesForm.get('topic').value),
      targetAPI: this.propertiesForm.get('targetAPI').value,
      source: this.templateForm.get('source').value,
      target: this.templateForm.get('target').value,
      active: this.propertiesForm.get('active').value,
      tested: this.mapping.tested || false,
      createNoExistingDevice: this.propertiesForm.get('createNoExistingDevice').value || false,
      qos: this.propertiesForm.get('qos').value,
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

  onMappingJsonPathChanged() {
    this.checkIsSubstitutionsValid();
    //if (this.isSubstitutionValid) {
    const n = this.jsonPathForm.get('variableNames').value.split(",");
    const p = this.jsonPathForm.get('variableJsonPathes').value.split(",");
    let s: MQTTMappingSubstitution[] = [];
    for (let index = 0; index < p.length; index++) {
      s.push({
        name: n[index].trim(),
        jsonPath: p[index].trim()
      });
    }
    this.mapping.substitutions = s;
    //}
  }


  checkIsSubstitutionsValid(): boolean {
    let result = false;
    const p = this.jsonPathForm.get('variableJsonPathes').value;
    const n = this.jsonPathForm.get('variableNames').value;
    console.log("Test if substitution is complete:", p, n);
    if (p != '' && n != '') {
      const pl = (p.match(/,/g) || []).length;
      const nl = (n.match(/,/g) || []).length;
      console.log("Test if substitution is complete:", pl, nl);
      if (nl == pl) {
        result = true;
      }
      /*      else if (this.wildcardTopic && nl+1 == pl){
             result = true;
           } */
    }
    this.isSubstitutionValid = result;
    return result;
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

  getVariableJsonPathes(): string {
    let p = '';
    if (!this.mapping.substitutions) this.mapping.substitutions = [];
    this.mapping.substitutions.forEach((m, i) => {
      if (i !== this.mapping.substitutions.length - 1) {
        p = p.concat(m.jsonPath).concat(', ')
      } else {
        p = p.concat(m.jsonPath)
      }
    }
    )
    //console.log("Variable:", v, p)
    return p;
  }

  public isUpdateExistingMapping(): boolean {
    return !!this.mapping;
  }

  async onTestTransformationClicked() {
    let test_mapping: MQTTMapping = {
      id: this.mapping.id,
      topic: this.normalizeTopic(this.propertiesForm.get('topic').value),
      targetAPI: this.propertiesForm.get('targetAPI').value,
      source: this.templateForm.get('source').value,
      target: this.templateForm.get('target').value,
      active: this.propertiesForm.get('active').value,
      tested: this.mapping.tested || false,
      createNoExistingDevice: this.propertiesForm.get('createNoExistingDevice').value || false,
      qos: this.propertiesForm.get('qos').value,
      substitutions: this.mapping.substitutions,
      lastUpdate: Date.now(),
    }
    let testResult = await this.mqttMappingService.testResult(test_mapping, false);
    this.testForm.patchValue({
      testResult: testResult,
    });
  }

  async onSendTestClicked() {
    let test_mapping: MQTTMapping = {
      id: this.mapping.id,
      topic: this.normalizeTopic(this.propertiesForm.get('topic').value),
      targetAPI: this.propertiesForm.get('targetAPI').value,
      source: this.templateForm.get('source').value,
      target: this.templateForm.get('target').value,
      active: this.propertiesForm.get('active').value,
      tested: this.mapping.tested || false,
      createNoExistingDevice: this.propertiesForm.get('createNoExistingDevice').value || false,
      qos: this.propertiesForm.get('qos').value,
      substitutions: this.mapping.substitutions,
      lastUpdate: Date.now(),
    }
    let { data, res } = await this.mqttMappingService.sendTestResult(test_mapping);
    //console.log ("My data:", data );
    this.testForm.patchValue({
      testResult: JSON.stringify(data, null, 4)
    });
    if (res.status == 200 || res.status == 201) {
      this.alertService.success("Successfully tested mapping!");
      this.mapping.tested = true;
    } else {
      let error = await res.text();
      this.alertService.danger("Failed to tested mapping: " + error);
    }
  }

  updateVariables(): void {
    const p = this.templateForm.get('target').value;
    //const v = p.match(/\$\d/g);
    const v = p.match(/\$\{(\w+)\}/g)
    console.log("Variable:", v, p)
    this.jsonPathForm.patchValue({
      variableNames: v.join(),
    });
  }

  async onSampleButtonClicked() {
    let current_target_api = this.propertiesForm.get('targetAPI').value;
    this.templateForm.patchValue({
      target: SAMPLE_TEMPLATES[current_target_api],
    });
  }

  async onCancelButtonClicked() {
    this.onCancel.emit();
  }

  public onNextSelected(event: { stepper: C8yStepper; step: CdkStep }): void {
    const source = this.templateForm.get('source').value
    const target = this.templateForm.get('target').value
    const targetAPI = this.propertiesForm.get('targetAPI').value
    //console.log("Source", source,this.mapping.source, event.step)
    console.log("OnNextSelected: /" + target + "/", event.step.label, targetAPI, target, target == '')
    if (event.step.label == "Define templates") {
      // path jsonPathForm
      this.jsonPathForm.patchValue({
        source: source,
      });
      this.updateVariables()
      this.checkIsSubstitutionsValid()

    } else if (event.step.label == "Define topic") {
      console.log("Populate jsonPath if wildcard:", event.step.label, this.isWildcardTopic(), this.mapping.substitutions.length)
      if (this.mapping.substitutions.length == 0 && this.isWildcardTopic()) {
        this.jsonPathForm.patchValue({
          variableJsonPathes: this.TOPIC_JSON_PATH,
        });
      }
      if (target == '') {
        // define target template for new mappings, i.e. target is not yet defined
        this.templateForm.patchValue({
          target: this.SAMPLE_TEMPLATES[targetAPI],
        });
      }
      if (targetAPI == "event") {
        this.editorOptionsSource.schema = SCHEMA_EVENT;
        this.editorOptionsTarget.schema = SCHEMA_EVENT;
      } else if (targetAPI == "alarm") {
        this.editorOptionsSource.schema = SCHEMA_ALARM;
        this.editorOptionsTarget.schema = SCHEMA_ALARM;
      } else if (targetAPI == "measurement") {
        this.editorOptionsSource.schema = SCHEMA_MEASUREMENT;
        this.editorOptionsTarget.schema = SCHEMA_MEASUREMENT;
      }

      //this.dataSource = JSON.parse(this.mapping.source);
      this.dataSource = {
        'source': {
          'id': '11111111111'
        },
        'type': 'c8y_LockAlarm',
        'text': 'This door is locked and it is an alrm!',
        'time': '2022-08-05T00:14:49.389+02:00',
        'severity': 'MINOR'
      };
      this.dataTarget = JSON.parse(this.mapping.target);
    } else if (event.step.label == "Define mapping") {

    }

    event.stepper.next();
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
