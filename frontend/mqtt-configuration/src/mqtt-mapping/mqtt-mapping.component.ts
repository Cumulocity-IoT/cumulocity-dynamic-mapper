import { Component, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { MQTTMappingService } from './mqtt-mapping.service';
import { ActionControl, AlertService, BuiltInActionType, Column, ColumnDataType, DataGridComponent, DisplayOptions, gettext, Pagination } from '@c8y/ngx-components';
import { MQTTMapping, SAMPLE_TEMPLATES } from '../mqtt-configuration.model';
import { StatusRendererComponent } from './status-cell.renderer.component';
import { QOSRendererComponent } from './qos-cell.renderer.component';
import { JSONPath } from 'jsonpath-plus';

@Component({
  selector: 'mqtt-mapping',
  templateUrl: 'mqtt-mapping.component.html',
  styleUrls: ['./mqtt-mapping.style.css', 
  '../../node_modules/jsoneditor/dist/jsoneditor.min.css'],
  encapsulation: ViewEncapsulation.None,
})

export class MQTTMappingComponent implements OnInit {

  isSubstitutionValid: boolean;

  @ViewChild(DataGridComponent) mappingGridComponent: DataGridComponent

  showConfigMapping: boolean = false;

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

  value: string;

  pagination: Pagination = {
    pageSize: 30,
    currentPage: 1,
  };
  actionControls: ActionControl[] = [];

  mappingToUpdate: MQTTMapping;

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
  }

  async addMapping() {
    let l = Math.max(...this.mqttMappings.map(item => item.id));
    let mapping = {
      id: l + 1,
      topic: '',
      targetAPI: 'measurement',
      source: '{}',
      target: '',
      active: false,
      tested: false,
      createNoExistingDevice: false,
      qos: 1,
      substitutions: [],
      lastUpdate: Date.now()
    }
    this.mappingToUpdate = mapping;
    console.log("Add mappping", l, this.mqttMappings)
    this.mappingGridComponent.reload();
    this.showConfigMapping = true;
  }

  editMapping(mapping: MQTTMapping) {
    this.mappingToUpdate = mapping;
    console.log("Editing mapping", mapping)
    this.showConfigMapping = true;
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

  async onCommit(mapping: MQTTMapping) {
    mapping.lastUpdate =  Date.now();
    let i = this.mqttMappings.map(item => item.id).findIndex(m => m == mapping.id)
    console.log("Changed mapping:", mapping, i);

    if (this.isUniqueTopic(mapping)) {
      if ( i == -1 ) {
        console.log("Push new mapping:", mapping, i);
        this.mqttMappings.push(mapping)
      } else {
        console.log("Update old new mapping:", mapping, i);
        this.mqttMappings[i] = mapping;
      }
      this.mappingGridComponent.reload();
    } else {
      this.alertService.danger(gettext('Topic is already used: ' + mapping.topic + ". Please use a different topic."));
    }
    this.showConfigMapping = false;
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
