import { Component, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { MQTTMappingService } from './mqtt-mapping.service';
import { ActionControl, AlertService, BuiltInActionType, Column, ColumnDataType, DataGridComponent, DisplayOptions, gettext, Pagination } from '@c8y/ngx-components';
import { API, Mapping, SnoopStatus } from '../shared/mqtt-configuration.model';
import { StatusRendererComponent } from './status-cell.renderer.component';
import { QOSRendererComponent } from './qos-cell.renderer.component';
import { TemplateRendererComponent } from './template.renderer.component';
import { SnoopedTemplateRendererComponent } from './snoopedTemplate.renderer.component';
import { isTemplateTopicUnique, SAMPLE_TEMPLATES } from '../shared/mqtt-helper';

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

  mappings: Mapping[] = [];
  mappingToUpdate: Mapping;
  editMode: boolean;

  displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: false,
    gridHeader: true
  };

  columns: Column[] = [
    {
      name: 'id',
      header: '#',
      path: 'id',
      filterable: false,
      dataType: ColumnDataType.TextShort,
      gridTrackSize: '3%'
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
      gridTrackSize: '7.5%'
    },
    {
      header: 'Sample payload',
      name: 'source',
      path: 'source',
      filterable: true,
      cellRendererComponent: TemplateRendererComponent,
      gridTrackSize: '25%'
    },
    {
      header: 'Target',
      name: 'target',
      path: 'target',
      filterable: true,
      cellRendererComponent: TemplateRendererComponent,
      gridTrackSize: '25%'
    },
    {
      header: 'Active-Tested-Snooping',
      name: 'active',
      path: 'active',
      filterable: false,
      cellRendererComponent: StatusRendererComponent,
      cellCSSClassName: 'textAlignCenter',
      gridTrackSize: '5%'
    },
    {
      header: 'QOS',
      name: 'qos',
      path: 'qos',
      filterable: true,
      cellRendererComponent: QOSRendererComponent,
      gridTrackSize: '7.5%'
    },
    {
      header: '# Snoopes',
      name: 'snoopedTemplates',
      path: 'snoopedTemplates',
      filterable: false,
      cellRendererComponent: SnoopedTemplateRendererComponent,
      gridTrackSize: '7.5%'
    },
  ]

  value: string;

  pagination: Pagination = {
    pageSize: 3,
    currentPage: 1,
  };
  actionControls: ActionControl[] = [];

  constructor(
    public mqttMappingService: MQTTMappingService,
    public alertService: AlertService
  ) { }


  ngOnInit() {
    this.loadMappings();
    this.actionControls.push({
      type: BuiltInActionType.Edit,
      callback: this.editMapping.bind(this)
    },
      {
        type: BuiltInActionType.Delete,
        callback: this.deleteMapping.bind(this)
      });
  }


  async addMapping() {
    this.editMode = false;
    let l = (this.mappings.length == 0 ? 0 :Math.max(...this.mappings.map(item => item.id))) + 1;
 
    let mapping = {
      id: l,
      topic: '',
      templateTopic: '',
      indexDeviceIdentifierInTemplateTopic: -1,
      targetAPI: API.MEASUREMENT,
      source: '{}',
      target: SAMPLE_TEMPLATES[API.MEASUREMENT],
      active: false,
      tested: false,
      createNoExistingDevice: false,
      qos: 1,
      substitutions: [],
      mapDeviceIdentifier: false,
      externalIdType: 'c8y_Serial',
      snoopTemplates: SnoopStatus.NONE,
      snoopedTemplates: [],
      lastUpdate: Date.now()
    }
    this.mappingToUpdate = mapping;
    console.log("Add mappping", l, this.mappings)
    this.mappingGridComponent.reload();
    this.showConfigMapping = true;
  }

  editMapping(mapping: Mapping) {
    this.editMode = true;
    this.mappingToUpdate = mapping;
    console.log("Editing mapping", mapping)
    this.showConfigMapping = true;
  }

  deleteMapping(mapping: Mapping) {
    console.log("Deleting mapping:", mapping)
    let i = this.mappings.map(item => item.id).findIndex(m => m == mapping.id) // find index of your object
    this.mappings.splice(i, 1) // remove it from array
    this.mappingGridComponent.reload();
  }

  async loadMappings(): Promise<void> {
    this.mappings = await this.mqttMappingService.loadMappings();
    if (!this.mappings) {
      this.mappings = await this.mqttMappingService.initalizeMappings();
    }
  }

  async onCommit(mapping: Mapping) {
    mapping.lastUpdate =  Date.now();
    let i = this.mappings.map(item => item.id).findIndex(m => m == mapping.id)
    console.log("Changed mapping:", mapping, i);

    if (isTemplateTopicUnique(mapping, this.mappings)) {
      if ( i == -1 ) {
        console.log("Push new mapping:", mapping, i);
        this.mappings.push(mapping)
      } else {
        console.log("Update old new mapping:", mapping, i);
        this.mappings[i] = mapping;
      }
      this.mappingGridComponent.reload();
    } else {
      this.alertService.danger(gettext('Topic is already used: ' + mapping.topic + ". Please use a different topic."));
    }
    this.showConfigMapping = false;
  }

  async onSaveButtonClicked() {
    this.saveMappings();
  }

  private async saveMappings() {
    const response1 = await this.mqttMappingService.saveMappings(this.mappings);
    const response2 = await this.mqttMappingService.reloadMappings();
    console.log("New response:", response1.res, response2, this.mappings)

    if (response1.res.ok && response2.status < 300) {
      this.alertService.success(gettext('Mappings saved and activated successfully'));
      this.isConnectionToMQTTEstablished = true;
    } else {
      this.alertService.danger(gettext('Failed to save mappings'));
    }
  }

}
