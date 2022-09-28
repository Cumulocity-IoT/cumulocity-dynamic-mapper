import { Component, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { ActionControl, AlertService, BuiltInActionType, Column, ColumnDataType, DataGridComponent, DisplayOptions, gettext, Pagination } from '@c8y/ngx-components';
import { API, Mapping, SnoopStatus } from '../../shared/configuration.model';
import { isTemplateTopicUnique, SAMPLE_TEMPLATES } from '../../shared/helper';
import { APIRendererComponent } from '../renderer/api.renderer.component';
import { QOSRendererComponent } from '../renderer/qos-cell.renderer.component';
import { SnoopedTemplateRendererComponent } from '../renderer/snoopedTemplate.renderer.component';
import { StatusRendererComponent } from '../renderer/status-cell.renderer.component';
import { TemplateRendererComponent } from '../renderer/template.renderer.component';
import { MappingService } from '../shared/mapping.service';

@Component({
  selector: 'mapping-grid',
  templateUrl: 'mapping.component.html',
  styleUrls: ['../shared/mapping.style.css',
    '../../../node_modules/jsoneditor/dist/jsoneditor.min.css'],
  encapsulation: ViewEncapsulation.None,
})

export class MappingComponent implements OnInit {

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
      header: 'Subscription Topic',
      name: 'subscriptionTopic',
      path: 'subscriptionTopic',
      filterable: true,
      gridTrackSize: '10%'
    },
    {
      header: 'Template Topic',
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
      sortable: false,
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
      gridTrackSize: '22.5%'
    },
    {
      header: 'Target',
      name: 'target',
      path: 'target',
      filterable: true,
      cellRendererComponent: TemplateRendererComponent,
      gridTrackSize: '22.5%'
    },
    {
      header: 'Active-Tested-Snooping',
      name: 'active',
      path: 'active',
      filterable: false,
      sortable: false,
      cellRendererComponent: StatusRendererComponent,
      cellCSSClassName: 'text-align-center',
      gridTrackSize: '12.5%'
    },
    {
      header: 'QOS',
      name: 'qos',
      path: 'qos',
      filterable: true,
      sortable: false,
      cellRendererComponent: QOSRendererComponent,
      gridTrackSize: '5%'
    },
/*     {
      header: '# Snoopes',
      name: 'snoopedTemplates',
      path: 'snoopedTemplates',
      filterable: false,
      sortable: false,
      cellRendererComponent: SnoopedTemplateRendererComponent,
      gridTrackSize: '5%'
    }, */
  ]

  value: string;

  pagination: Pagination = {
    pageSize: 3,
    currentPage: 1,
  };
  actionControls: ActionControl[] = [];

  constructor(
    public mappingService: MappingService,
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
    let l = (this.mappings.length == 0 ? 0 : Math.max(...this.mappings.map(item => item.id))) + 1;

    let mapping = {
      id: l,
      subscriptionTopic: '',
      templateTopic: '',
      indexDeviceIdentifierInTemplateTopic: -1,
      targetAPI: API.MEASUREMENT,
      source: '{}',
      target: SAMPLE_TEMPLATES[API.MEASUREMENT],
      active: false,
      tested: false,
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
    // create deep copy of existing mapping, in case user cancels changes
    this.mappingToUpdate = JSON.parse(JSON.stringify(mapping));
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

  /*   async loadMappings(): Promise<void> {
  } */

  async loadMappings(): Promise<void> {
    this.mappings = await this.mappingService.loadMappings();
    /*      this.mqttMappingService.loadMappings().then( mappings => {
          this.mappings = mappings
        })  */
  }

  async onCommit(mapping: Mapping) {
    mapping.lastUpdate = Date.now();
    let i = this.mappings.map(item => item.id).findIndex(m => m == mapping.id)
    console.log("Changed mapping:", mapping, i);

    if (isTemplateTopicUnique(mapping, this.mappings)) {
      if (i == -1) {
        // new mapping
        console.log("Push new mapping:", mapping, i);
        this.mappings.push(mapping)
      } else {
        console.log("Update existing mapping:", this.mappings[i], mapping, i);
        this.mappings[i] = mapping;
      }
      this.mappingGridComponent.reload();
      this.saveMappings();
      this.activateMappings();
    } else {
      this.alertService.danger(gettext('Topic is already used: ' + mapping.subscriptionTopic + ". Please use a different topic."));
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
    const response2 = await this.mappingService.activateMappings();
    console.log("Activate mapping response:", response2)
    if (response2.status < 300) {
      this.alertService.success(gettext('Mappings activated successfully'));
      this.isConnectionToMQTTEstablished = true;
    } else {
      this.alertService.danger(gettext('Failed to activate mappings'));
    }
  }

  private async saveMappings() {
    const response1 = await this.mappingService.saveMappings(this.mappings);
    console.log("Saved mppings response:", response1.res, this.mappings)
    if (response1.res.ok) {
      this.alertService.success(gettext('Mappings saved successfully'));
      this.isConnectionToMQTTEstablished = true;
    } else {
      this.alertService.danger(gettext('Failed to save mappings'));
    }
  }

}
