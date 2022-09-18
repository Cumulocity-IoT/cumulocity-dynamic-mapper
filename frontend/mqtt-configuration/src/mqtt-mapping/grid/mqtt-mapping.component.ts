import { Component, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { ActionControl, AlertService, BuiltInActionType, Column, ColumnDataType, DataGridComponent, DisplayOptions, gettext, Pagination } from '@c8y/ngx-components';
import { from, Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { API, Mapping, SnoopStatus } from '../../shared/mqtt-configuration.model';
import { isTemplateTopicUnique, SAMPLE_TEMPLATES } from '../../shared/mqtt-helper';
import { APIRendererComponent } from '../renderer/api.renderer.component';
import { QOSRendererComponent } from '../renderer/qos-cell.renderer.component';
import { SnoopedTemplateRendererComponent } from '../renderer/snoopedTemplate.renderer.component';
import { StatusRendererComponent } from '../renderer/status-cell.renderer.component';
import { TemplateRendererComponent } from '../renderer/template.renderer.component';
import { MQTTMappingService } from '../shared/mqtt-mapping.service';

@Component({
  selector: 'mqtt-mapping',
  templateUrl: 'mqtt-mapping.component.html',
  styleUrls: ['../shared/mqtt-mapping.style.css', 
  '../../../node_modules/jsoneditor/dist/jsoneditor.min.css'],
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
      header: 'TemplateTopic',
      name: 'templateTopic',
      path: 'templateTopic',
      filterable: true,
      gridTrackSize: '10%'
    },
    {
      name: 'targetAPI',
      header: 'API',
      path: 'targetAPI',
      filterable: false,
      dataType: ColumnDataType.TextShort,
      cellRendererComponent: APIRendererComponent,
      gridTrackSize: '5%'
    },
    {
      header: 'Sample payload',
      name: 'source',
      path: 'source',
      filterable: true,
      cellRendererComponent: TemplateRendererComponent,
      gridTrackSize: '20%'
    },
    {
      header: 'Target',
      name: 'target',
      path: 'target',
      filterable: true,
      cellRendererComponent: TemplateRendererComponent,
      gridTrackSize: '20%'
    },
    {
      header: 'Active-Tested-Snooping',
      name: 'active',
      path: 'active',
      filterable: false,
      cellRendererComponent: StatusRendererComponent,
      cellCSSClassName: 'text-align-center',
      gridTrackSize: '7.5%'
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
  isMQTTAgentCreated$: Observable<boolean>;
  mqttAgentId$: Observable<string>;

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
    this.mqttAgentId$ = from(this.mqttMappingService.initializeMQTTAgent());
    this.isMQTTAgentCreated$ = this.mqttAgentId$.pipe(map( agentId => agentId != null));
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
    console.log("Trying to delete mapping, index", i)
    this.mappings.splice(i, 1) // remove it from array
    console.log("Deleting mapping, remaining maps", this.mappings)
    this.mappingGridComponent.reload();
    this.saveMappings();
    this.activateMappings();
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
      this.saveMappings();
      this.activateMappings();
    } else {
      this.alertService.danger(gettext('Topic is already used: ' + mapping.topic + ". Please use a different topic."));
    }
    this.showConfigMapping = false;
  }

  async onSaveClicked() {
    this.saveMappings();
  }

  async onActivateClicked() {
    this.activateMappings();
  }

  private async activateMappings() {
    const response2 = await this.mqttMappingService.activateMappings();
    console.log("Activate mapping response:", response2)
    if (response2.status < 300) {
      this.alertService.success(gettext('Mappings activated successfully'));
      this.isConnectionToMQTTEstablished = true;
    } else {
      this.alertService.danger(gettext('Failed to activate mappings'));
    }
  }

  private async saveMappings() {
    const response1 = await this.mqttMappingService.saveMappings(this.mappings);
    console.log("Saved mppings response:", response1.res, this.mappings)
    if (response1.res.ok) {
      this.alertService.success(gettext('Mappings saved successfully'));
      this.isConnectionToMQTTEstablished = true;
    } else {
      this.alertService.danger(gettext('Failed to save mappings'));
    }
  }

}
