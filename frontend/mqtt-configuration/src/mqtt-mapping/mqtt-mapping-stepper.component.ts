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
  substitutions: string = '';

  pathSource: string;
  pathTarget: string;
  dataSource: any;
  dataTarget: any;

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
      console.log("Set pathTarget:", path);
    }
  }.bind(this)

  editorOptionsSource: any = {
    modes: ['tree', 'code'],
    statusBar: false,
    enableSort: false,
    enableTransform: false,
    enableSearch: false,
    onEvent: this.setSelectionSource,
    //no default schema for source json schema: SCHEMA_EVENT
  };

  editorOptionsTarget: any = {
    modes: ['tree', 'code'],
    statusBar: false,
    enableSort: false,
    enableTransform: false,
    enableSearch: false,
    onEvent: this.setSelectionTarget,
    schema: SCHEMA_EVENT
  };


  @ViewChild('editorSource', { static: false }) editorSource!: JsonEditorComponent;
  @ViewChild('editorTarget', { static: false }) editorTarget!: JsonEditorComponent;

  @ViewChild(C8yStepper, { static: false })
  stepper: C8yStepper;

  showConfigMapping: boolean = false;

  isConnectionToMQTTEstablished: boolean;

  value: string;

  counterShowSubstitutions: number = 0;

  propertiesForm: FormGroup;
  templateForm: FormGroup;
  testForm: FormGroup;

  topicUnique: boolean;
  wildcardTopic: boolean;

  TOPIC_JSON_PATH = "TOPIC";
  dataResult: string;

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
    let dataResult = await this.mqttMappingService.testResult(test_mapping, false);
    this.dataResult = dataResult;
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
    if (res.status == 200 || res.status == 201) {
      this.alertService.success("Successfully tested mapping!");
      this.mapping.tested = true;
    } else {
      let error = await res.text();
      this.alertService.danger("Failed to tested mapping: " + error);
    }
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
    console.log("OnNextSelected: /" + target + "/", event.step.label, targetAPI, this.mapping.source, target == '')
    if (event.step.label == "Define templates") {

    } else if (event.step.label == "Define topic") {
      console.log("Populate jsonPath if wildcard:", event.step.label, this.isWildcardTopic(), this.mapping.substitutions.length)
      if (this.mapping.substitutions.length == 0 && this.isWildcardTopic()) {
        this.mapping.substitutions.push({pathSource: this.TOPIC_JSON_PATH, pathTarget: "source.id"})
      }

      this.mapping.substitutions.forEach( s => {
        //console.log ("New mapping:", s.pathSource, s.pathTarget);
        this.substitutions = this.substitutions + `[ ${s.pathSource} -> ${s.pathTarget}]`;
      } )

      if (target == '') {
        // define target template for new mappings, i.e. target is not yet defined
        this.templateForm.patchValue({
          target: this.SAMPLE_TEMPLATES[targetAPI],
        });
      }
      if (targetAPI == "event") {
        this.editorOptionsTarget.schema = SCHEMA_EVENT;
      } else if (targetAPI == "alarm") {
        this.editorOptionsTarget.schema = SCHEMA_ALARM;
      } else if (targetAPI == "measurement") {
        this.editorOptionsTarget.schema = SCHEMA_MEASUREMENT;
      }

      this.dataSource = JSON.parse(this.mapping.source);
/*       this.dataSource = {
        'source': {
          'id': '11111111111'
        },
        'type': 'c8y_LockAlarm',
        'text': 'This door is locked and it is an alrm!',
        'time': '2022-08-05T00:14:49.389+02:00',
        'severity': 'MINOR'
      }; */
      this.dataTarget = JSON.parse(this.mapping.target);
    } else if (event.step.label == "Define mapping") {

    }

    event.stepper.next();
  }

  public onAddMappingClicked(){
    let sub: MQTTMappingSubstitution  = {
      pathSource: this.pathSource,
      pathTarget: this.pathTarget
    }
    this.mapping.substitutions.push(sub);
    this.substitutions = this.substitutions + `[ ${sub.pathSource} -> ${sub.pathTarget}]`;
/*     this.mapping.substitutions.forEach( s => {
      //console.log ("New mapping:", s.pathSource, s.pathTarget);
      this.substitutions = this.substitutions + `[ ${s.pathSource} -> ${s.pathTarget}]`;
    } ) */
    console.log ("New mapping:", sub);
  }

  public onClearMappingsClicked(){
    this.mapping.substitutions = [];
    this.substitutions = "";
    console.log ("Cleared mappings!");
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
