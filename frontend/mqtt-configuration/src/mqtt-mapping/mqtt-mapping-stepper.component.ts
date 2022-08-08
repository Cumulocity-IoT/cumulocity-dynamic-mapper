import { Component, EventEmitter, Inject, Input, OnInit, Output, QueryList, ViewChild, ViewChildren, ViewEncapsulation } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { MQTTMappingService } from './mqtt-mapping.service';
import { AlertService, C8yStepper } from '@c8y/ngx-components';
import { MQTTMapping, MQTTMappingSubstitution, SAMPLE_TEMPLATES, APIs, QOSs } from "../mqtt-configuration.model"
import { MonacoEditorComponent, MonacoEditorLoaderService } from '@materia-ui/ngx-monaco-editor';
import { JSONPath } from 'jsonpath-plus';
import { filter, take } from "rxjs/operators";
import { CdkStep } from '@angular/cdk/stepper';

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

  isSubstitutionValid: boolean;

  editorOptionsJson = {
    theme: 'vs-dark',
    language: 'json',
    glyphMargin: false,
    lineNumbers: 'off',
    folding: true,
    lineDecorationsWidth: 0,
    lineNumbersMinChars: 0,
    minimap: {
      enabled: false
    }
  };

  editorOptionsReadOnlyJson = {
    theme: 'vs-dark',
    language: 'json',
    glyphMargin: false,
    lineNumbers: 'off',
    folding: true,
    readOnly: true,
    lineDecorationsWidth: 0,
    lineNumbersMinChars: 0,
    minimap: {
      enabled: false
    }
  };

  editorOptionsResult = {
    theme: 'vs-dark',
    language: 'javascript',
    glyphMargin: false,
    lineNumbers: 'off',
    folding: true,
    readOnly: true,
    domReadOnly: true,
    lineDecorationsWidth: 0,
    lineNumbersMinChars: 0,
    minimap: {
      enabled: false
    },
    onMonacoLoad: () => {
      console.log("In monaco onload");
    }
  };

  @ViewChildren(MonacoEditorComponent) monacoComponents: QueryList<MonacoEditorComponent>

  @ViewChild(C8yStepper, { static: false })
  stepper: C8yStepper;
  
  showConfigMapping: boolean = false;

  isConnectionToMQTTEstablished: boolean;

  value: string;

  propertiesForm: FormGroup;
  templateForm: FormGroup;
  jsonPathForm: FormGroup;
  testForm: FormGroup;

  isTopicUnique: boolean;

  constructor(
    public mqttMappingService: MQTTMappingService,
    public alertService: AlertService,
    private monacoLoaderService: MonacoEditorLoaderService) {
      this.monacoLoaderService.isMonacoLoaded$
        .pipe(
          filter(isLoaded => isLoaded),
          take(1)
        )
        .subscribe(() => {
          //console.log("Monaco editor loaded!");
          monaco.languages.json.jsonDefaults.setDiagnosticsOptions({
            validate: false,
          });
        });
    }

  ngOnInit() {
    //console.log("Mapping to be updated:", this.mapping);
    this.initForm();
  }

  private initForm(): void {
    this.propertiesForm = new FormGroup({
      topic: new FormControl(this.isUpdateExistingMapping()? this.mapping.topic: '', Validators.required),
      targetAPI: new FormControl(this.isUpdateExistingMapping()? this.mapping.targetAPI: '', Validators.required),
      active: new FormControl(this.isUpdateExistingMapping()? this.mapping.active: '', Validators.required),
      createNoExistingDevice: new FormControl(this.isUpdateExistingMapping()? this.mapping.createNoExistingDevice: '', Validators.required),
      qos: new FormControl(this.isUpdateExistingMapping()? this.mapping.qos: '', Validators.required),
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
    const p = this.jsonPathForm.get('path').value;
    const d = JSON.parse(this.templateForm.get('source').value);
    const r = JSON.stringify(JSONPath({ path: p, json: d }), null, 4);
    console.log("Changed jsonPath: ", p, d, r);
    this.jsonPathForm.patchValue({
      jsonPathResult: r,
    });
  }

  public checkTopic(e): boolean {
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
    this.isTopicUnique = result;
    // invalidate fields, since entry is not valid
    if (!result) this.propertiesForm.controls['topic'].setErrors({'incorrect': true});
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
      tested: this.mapping.tested||false,
      createNoExistingDevice: this.propertiesForm.get('createNoExistingDevice').value||false,
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
    nt = nt.concat(nt.endsWith("#") ? '' : '/')
    return nt
  }

  onFormatButtonClicked() {
    this.monacoComponents.forEach(mc => {
      if (mc.options && mc.options.language == 'json') {
        mc.editor.getAction('editor.action.formatDocument').run()
      }
    });
  }

  onMappingJsonPathChanged() {
    this.updateSubstitutions();
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


  updateSubstitutions(): boolean {
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
      tested: this.mapping.tested||false,
      createNoExistingDevice: this.propertiesForm.get('createNoExistingDevice').value||false,
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
      tested: this.mapping.tested||false,
      createNoExistingDevice: this.propertiesForm.get('createNoExistingDevice').value||false,
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
      this.alertService.success ("Successfully tested mapping!");
      this.mapping.tested = true;
    } else {
      let error = await res.text();
      this.alertService.danger ("Failed to tested mapping: " + error);
    }
  }

  updateVariables(): void  {
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
    this.monacoComponents.forEach(mc => {
      mc.editor.getAction('editor.action.formatDocument').run();
    });
  }

  async onCancelButtonClicked() {
    this.onCancel.emit();
  }

  public onNextSelected(event: { stepper: C8yStepper; step: CdkStep }): void {
    const source = this.templateForm.get('source').value
    //console.log("Source", source,this.mapping.source, event.step)
    console.log("Source", event.step, event)
    if (event.step.label == "Define templates") {
      // path jsonPathForm
      this.jsonPathForm.patchValue ({
        source: source,
      });
      this.updateVariables()
      this.updateSubstitutions()
      
    } 
    event.stepper.next();
  }

}
