import { Component, OnInit, QueryList, ViewChild, ViewChildren, ViewEncapsulation } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { MQTTMappingService } from './mqtt-mapping.service';
import { ActionControl, AlertService, BuiltInActionType, Column, ColumnDataType, DataGridComponent, DisplayOptions, gettext, Pagination } from '@c8y/ngx-components';
import { MQTTMapping } from 'src/mqtt-configuration.model';
import { MonacoEditorComponent } from '@materia-ui/ngx-monaco-editor';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { StatusRendererComponent } from './status-cell.renderer.component';

@Component({
  selector: 'mqtt-mapping',
  templateUrl: 'mqtt-mapping.component.html',
  styleUrls: ['./mqtt-mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
})

export class MQTTMappingComponent implements OnInit {

  editorOptions = {
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
      cellCSSClassName: 'col'
    },
    {
      header: 'Topic',
      name: 'topic',
      path: 'topic',
      filterable: true,
      cellCSSClassName: 'col'
    },
    {
      name: 'targetAPI',
      header: 'Target API',
      path: 'targetAPI',
      filterable: true,
      dataType: ColumnDataType.TextShort,
      cellCSSClassName: 'col'
    },
    {
      header: 'Source',
      name: 'source',
      path: 'source',
      filterable: true,
      cellCSSClassName: 'col-4'
    },
    {
      header: 'Target',
      name: 'target',
      path: 'target',
      filterable: true,
      cellCSSClassName: 'col-4'
    },
    {
      header: 'Active',
      name: 'active',
      path: 'active',
      filterable: true,
      cellRendererComponent: StatusRendererComponent,
      cellCSSClassName: 'col'
    },
    {
      header: 'QOS',
      name: 'qos',
      path: 'qos',
      filterable: true,
      cellCSSClassName: 'col'
    },
  ]

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


  constructor(
    private bsModalService: BsModalService,
    public mqttMappingService: MQTTMappingService,
    public alertservice: AlertService
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
      qos: new FormControl('', Validators.required),
    });
  }

  async addMapping() {
    let l = Math.max(...this.mqttMappings.map(item => item.id));
    this.mqttMappings.push({
      id: l + 1,
      topic: '',
      targetAPI: '',
      source: '{}',
      target: '{}',
      active: false,
      qos: 1,
      lastUpdate: Date.now()
    })
    console.log("Add mappping", l, this.mqttMappings)
    this.mappingGridComponent.reload();
  }


  editMapping(mapping: MQTTMapping) {
    console.log("Editing mapping", mapping)
    this.mappingForm.patchValue({
      id: mapping.id,
      topic: mapping.topic,
      targetAPI: mapping.targetAPI,
      source: mapping.source,
      target: mapping.target,
      active: mapping.active,
      qos: mapping.qos,
    });
    this.onFormatButtonClicked();
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

  async onCommitButtonClicked() {
    let changed_mapping: MQTTMapping = {
      id: this.mappingForm.get('id').value,
      topic: this.normalizeTopic(this.mappingForm.get('topic').value),
      targetAPI: this.mappingForm.get('targetAPI').value,
      source: this.mappingForm.get('source').value,
      target: this.mappingForm.get('target').value,
      active: this.mappingForm.get('active').value,
      qos: this.mappingForm.get('qos').value,
      lastUpdate: Date.now(),
    }
    console.log("Changed mapping:", changed_mapping);
    let i = this.mqttMappings.map(item => item.id).findIndex(m => m == changed_mapping.id)

    if (this.isUniqueTopic(changed_mapping)) {
      this.mqttMappings[i] = changed_mapping;
      this.mappingGridComponent.reload();
    } else {
      this.alertservice.danger(gettext('Topic is already used: ' + changed_mapping.topic + ". Please use a different topic."));
    }
  }

  private normalizeTopic(topic: string) {
    return topic.trim().replace(/\/+$/, '').replace(/^\/+/, '')
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

  async onFormatButtonClicked() {
    this.monacoComponents.forEach(mc => mc.editor.getAction('editor.action.formatDocument').run());
  }

  private async saveMappings() {
    const response1 = await this.mqttMappingService.saveMappings(this.mqttMappings);
    const response2 = await this.mqttMappingService.reloadMappings();

    if (response1.res.status === 200 && response2.status === 200) {
      this.alertservice.success(gettext('Mappings saved and activated successfully'));
      this.isConnectionToMQTTEstablished = true;
    } else {
      this.alertservice.danger(gettext('Failed to save mappings'));
    }

  }

}
