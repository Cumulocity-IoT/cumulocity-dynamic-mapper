import { Component, OnInit, QueryList, ViewChild, ViewChildren, ViewEncapsulation } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { MQTTMappingService } from './mqtt-mapping.service';
import { ActionControl, AlertService, BuiltInActionType, Column, ColumnDataType, DataGridComponent, DisplayOptions, gettext, Pagination } from '@c8y/ngx-components';
import { MQTTMapping, MQTTMappingSubstitution } from 'src/mqtt-configuration.model';
import { MonacoEditorComponent } from '@materia-ui/ngx-monaco-editor';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { StatusRendererComponent } from './status-cell.renderer.component';
import { QOSRendererComponent } from './qos-cell.renderer.component';
import { JSONPath } from 'jsonpath-plus';

@Component({
  selector: 'mqtt-mapping',
  templateUrl: 'mqtt-mapping.component.html',
  styleUrls: ['./mqtt-mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
})

export class MQTTMappingComponent implements OnInit {

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
    },
    onMonacoLoad: () => {
      console.log("In monaco onload");
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
  @ViewChild(DataGridComponent) mappingGridComponent: DataGridComponent

  //this.monacoComponent.editor

  source: string = `{"device": "%%1", "value": %%2, "timestamp": "%%3"}`;
  target: string = `{ 
    "source": {
      "id": "%%1" 
    }, 
    "time": "%%3",
    "type": "c8y_TemperatureMeasurement",
    "c8y_Steam": {
      "Temperature": {
        "unit": "C",
        "value": %%2
      }
    }
  }`;
  isConnectionToMQTTEstablished: boolean;

  mqttMappings: MQTTMapping[];

  displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: false,
    gridHeader: true
  };

  columns: Column[] = [
    {
      name: 'id',
      header: 'ID',
      path: 'id',
      filterable: true,
      dataType: ColumnDataType.TextShort,
      gridTrackSize: '5%'
    },
    {
      header: 'Topic',
      name: 'topic',
      path: 'topic',
      filterable: true,
      gridTrackSize: '10%'
    },
    {
      name: 'targetAPI',
      header: 'Target API',
      path: 'targetAPI',
      filterable: true,
      dataType: ColumnDataType.TextShort,
      gridTrackSize: '10%'
    },
    {
      header: 'Sample payload',
      name: 'source',
      path: 'source',
      filterable: true,
      gridTrackSize: '25%'
    },
    {
      header: 'Target',
      name: 'target',
      path: 'target',
      filterable: true,
      gridTrackSize: '25%'
    },
    {
      header: 'Active-Tested',
      name: 'active',
      path: 'active',
      filterable: true,
      cellRendererComponent: StatusRendererComponent,
      gridTrackSize: '5%'
    },
    {
      header: 'QOS',
      name: 'qos',
      path: 'qos',
      filterable: true,
      cellRendererComponent: QOSRendererComponent,
      gridTrackSize: '10%'
    },
  ]

  SAMPLE_TEMPLATES = {
    measurement: `
    {                                               
      \"c8y_TemperatureMeasurement\": {
          \"T\": {
              \"value\": 25,
                \"unit\": \"C\" }
            },
        \"time\":\"2013-06-22T17:03:14.000+02:00\",
        \"source\": {
          \"id\":\"10200\" },
        \"type\": \"c8y_TemperatureMeasurement\"
    }`,
    alarm: `
    {                                            
      \"source\": {
      \"id\": \"251982\"
      },        \
      \"type\": \"c8y_UnavailabilityAlarm\",
      \"text\": \"No data received from the device within the required interval.\",
      \"severity\": \"MAJOR\",
      \"status\": \"ACTIVE\",
      \"time\": \"2020-03-19T12:03:27.845Z\"
    }`,
    event: `
    { 
      \"source\": {
      \"id\": \"251982\"
      },
      \"text\": \"Sms sent: Alarm occurred\",
      \"time\": \"2020-03-19T12:03:27.845Z\",
      \"type\": \"c8y_OutgoingSmsLog\"
   }`
  }

  APIs = ['measurement', 'event', 'alarm']

  QOSs = [{ name: 'At most once', value: 0 },
  { name: 'At least once', value: 1 },
  { name: 'Exactly once', value: 2 }]

  value: string;

  pagination: Pagination = {
    pageSize: 30,
    currentPage: 1,
  };
  actionControls: ActionControl[] = [];

  mappingForm: FormGroup;

  jsonPathForm: FormGroup;
  mapping: MQTTMapping;

  constructor(
    public mqttMappingService: MQTTMappingService,
    public alertService: AlertService
  ) { }


  ngOnInit() {
    this.initMappingDetails();
    this.initForm();
    this.actionControls.push({
      type: BuiltInActionType.Edit,
      callback: this.editMapping.bind(this)
    },
      {
        type: BuiltInActionType.Delete,
        callback: this.deleteMapping.bind(this)
      });
  }

  private initForm(): void {
    this.mappingForm = new FormGroup({
      id: new FormControl('', Validators.required),
      topic: new FormControl('', Validators.required),
      targetAPI: new FormControl('', Validators.required),
      source: new FormControl('', Validators.required),
      target: new FormControl('', Validators.required),
      active: new FormControl('', Validators.required),
      tested: new FormControl('', Validators.required),
      createNoExistingDevice: new FormControl('', Validators.required),
      qos: new FormControl('', Validators.required),
    });

    this.jsonPathForm = new FormGroup({
      path: new FormControl('', Validators.required),
      result: new FormControl('', Validators.required),
      variableNames: new FormControl('', Validators.required),
      variableJsonPathes: new FormControl('', Validators.required),
      testResult: new FormControl('', Validators.required),
    });
  }

  async addMapping() {
    let l = Math.max(...this.mqttMappings.map(item => item.id));
    this.mqttMappings.push({
      id: l + 1,
      topic: '',
      targetAPI: 'measurement',
      source: '{}',
      target: this.SAMPLE_TEMPLATES['measurement'],
      active: false,
      tested: false,
      createNoExistingDevice: false,
      qos: 1,
      substitutions: [],
      lastUpdate: Date.now()
    })
    console.log("Add mappping", l, this.mqttMappings)
    this.mappingGridComponent.reload();
  }

  editMapping(mapping: MQTTMapping) {
    this.mapping = mapping;
    console.log("Editing mapping", mapping)
    this.mappingForm.patchValue({
      id: mapping.id,
      topic: mapping.topic,
      targetAPI: mapping.targetAPI,
      source: mapping.source,
      target: mapping.target,
      active: mapping.active,
      tested: mapping.tested,
      createNoExistingDevice: mapping.createNoExistingDevice,
      qos: mapping.qos,
    });
    this.monacoComponents.forEach(mc => {
      if (mc.options && mc.options.language == 'json') {
        mc.editor.getAction('editor.action.formatDocument').run()
      }
    });

    this.jsonPathForm.patchValue({
      variableJsonPathes: this.getVariableJsonPathes(mapping),
      variableNames: this.getVariableNames(mapping),
    });

    this.isSubstitutionValid = this.checkSubstitutions();
  }

  deleteMapping(mapping: MQTTMapping) {
    console.log("Deleting mapping:", mapping)
    let i = this.mqttMappings.map(item => item.id).findIndex(m => m == mapping.id) // find index of your object
    this.mqttMappings.splice(i, 1) // remove it from array
    this.mappingGridComponent.reload();
  }

  private async initMappingDetails(): Promise<void> {
    this.mqttMappings = await this.mqttMappingService.loadMappings();
    if (!this.mqttMappings) {
      return;
    }
  }

  onJsonPathChanged() {
    const p = this.jsonPathForm.get('path').value;
    const d = JSON.parse(this.mappingForm.get('source').value);
    const r = JSON.stringify(JSONPath({ path: p, json: d }), null, 4);
    console.log("Changed jsonPath: ", p, d, r);
    this.jsonPathForm.patchValue({
      result: r,
    });
  }

  async onCommitButtonClicked() {
    let changed_mapping: MQTTMapping = {
      id: this.mappingForm.get('id').value,
      topic: this.normalizeTopic(this.mappingForm.get('topic').value),
      targetAPI: this.mappingForm.get('targetAPI').value,
      source: this.mappingForm.get('source').value,
      target: this.mappingForm.get('target').value,
      active: this.mappingForm.get('active').value,
      tested: this.mapping.tested||false,
      createNoExistingDevice: this.mapping.createNoExistingDevice||false,
      qos: this.mappingForm.get('qos').value,
      substitutions: this.mapping.substitutions,
      lastUpdate: Date.now(),
    }
    console.log("Changed mapping:", changed_mapping);
    let i = this.mqttMappings.map(item => item.id).findIndex(m => m == changed_mapping.id)

    if (this.isUniqueTopic(changed_mapping)) {
      this.mqttMappings[i] = changed_mapping;
      this.mappingGridComponent.reload();
    } else {
      this.alertService.danger(gettext('Topic is already used: ' + changed_mapping.topic + ". Please use a different topic."));
    }
  }

  private normalizeTopic(topic: string) {
    let nt = topic.trim().replace(/\/+$/, '').replace(/^\/+/, '')
    console.log("Topic test", topic, nt);
    // append trailing slash if last character is not wildcard #
    nt = nt.concat(nt.endsWith("#") ? '' : '/')
    return nt
  }

  private isUniqueTopic(new_map: MQTTMapping): boolean {
    let result = this.mqttMappings.every(m => {
      if (new_map.topic == m.topic && new_map.id != m.id) {
        return false;
      }
      return true;
    })
    return result;
  }

  async onSaveButtonClicked() {
    this.saveMappings();
  }

  onFormatButtonClicked() {
    this.monacoComponents.forEach(mc => {
      if (mc.options && mc.options.language == 'json') {
        mc.editor.getAction('editor.action.formatDocument').run()
      }
    });
  }

  onMappingJsonPathChanged() {
    this.isSubstitutionValid = this.checkSubstitutions();
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


  checkSubstitutions(): boolean {
    const p = this.jsonPathForm.get('variableJsonPathes').value;
    const n = this.jsonPathForm.get('variableNames').value;
    console.log("Test if substitution is complete:", p, n);
    if (p != '' && n != '') {
      const pl = (p.match(/,/g) || []).length;
      const nl = (n.match(/,/g) || []).length;
      console.log("Test if substitution is complete:", pl, nl);
      if (nl == pl) {
        return true;
      }
    }
    return false;
  }

  getVariableNames(mapping: MQTTMapping): string {
    const p = mapping.target;
    // variable name:$1, $2, $3
    //const v = p.match(/\$\d/g)||[];
    // variable name:${wert}, ${time}, ${type}
    const v = p.match(/\$\{(\w+)\}/g) || [];

    //console.log("Variable:", v, p)
    return v.join();
  }

  getVariableJsonPathes(mapping: MQTTMapping): string {
    let p = '';
    if (!mapping.substitutions) mapping.substitutions = [];
    mapping.substitutions.forEach((m, i) => {
      if (i !== mapping.substitutions.length - 1) {
        p = p.concat(m.jsonPath).concat(', ')
      } else {
        p = p.concat(m.jsonPath)
      }
    }
    )
    //console.log("Variable:", v, p)
    return p;
  }

  async onTransformClicked() {
    let test_mapping: MQTTMapping = {
      id: this.mappingForm.get('id').value,
      topic: this.normalizeTopic(this.mappingForm.get('topic').value),
      targetAPI: this.mappingForm.get('targetAPI').value,
      source: this.mappingForm.get('source').value,
      target: this.mappingForm.get('target').value,
      active: this.mappingForm.get('active').value,
      tested: this.mappingForm.get('tested').value,
      createNoExistingDevice: this.mappingForm.get('createNoExistingDevice').value,
      qos: this.mappingForm.get('qos').value,
      substitutions: this.mapping.substitutions,
      lastUpdate: Date.now(),
    }
    let testResult = await this.mqttMappingService.testResult(test_mapping, false);
    this.jsonPathForm.patchValue({
      testResult: testResult,
    });
  }

  async onSendTestClicked() {
    let test_mapping: MQTTMapping = {
      id: this.mappingForm.get('id').value,
      topic: this.normalizeTopic(this.mappingForm.get('topic').value),
      targetAPI: this.mappingForm.get('targetAPI').value,
      source: this.mappingForm.get('source').value,
      target: this.mappingForm.get('target').value,
      active: this.mappingForm.get('active').value,
      tested: this.mappingForm.get('tested').value,
      createNoExistingDevice: this.mappingForm.get('createNoExistingDevice').value,
      qos: this.mappingForm.get('qos').value,
      substitutions: this.mapping.substitutions,
      lastUpdate: Date.now(),
    }
    let { data, res } = await this.mqttMappingService.sendTestResult(test_mapping);
    console.log ("My data:", data );
    this.jsonPathForm.patchValue({
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

  trackVariables() {
    const p = this.mappingForm.get('target').value;
    //const v = p.match(/\$\d/g);
    const v = p.match(/\$\{(\w+)\}/g)
    console.log("Variable:", v, p)
    this.jsonPathForm.patchValue({
      variableNames: v.join(),
    });
  }

  async onSampleButtonClicked() {
    let curret_target_api = this.mappingForm.get('targetAPI').value;
    this.mappingForm.patchValue({
      target: this.SAMPLE_TEMPLATES[curret_target_api],
    });
    this.monacoComponents.forEach(mc => {
      mc.editor.getAction('editor.action.formatDocument').run();
    });
  }

  private async saveMappings() {
    const response1 = await this.mqttMappingService.saveMappings(this.mqttMappings);
    const response2 = await this.mqttMappingService.reloadMappings();

    if (response1.res.status === 200 && response2.status === 200) {
      this.alertService.success(gettext('Mappings saved and activated successfully'));
      this.isConnectionToMQTTEstablished = true;
    } else {
      this.alertService.danger(gettext('Failed to save mappings'));
    }
  }

}
