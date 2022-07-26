import { Component, OnInit } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { MQTTMappingService } from './mqtt-mapping.service';
import { ActionControl, AlertService, Column, ColumnDataType, DisplayOptions, gettext, Pagination } from '@c8y/ngx-components';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { MQTTMapping } from 'src/mqtt-configuration.model';

@Component({
  selector: 'mqtt-mapping',
  templateUrl: 'mqtt-mapping.component.html',
})
export class MQTTMappingComponent implements OnInit {
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
    },
    {
      header: 'Topic',
      name: 'topic',
      path: 'topic',
      filterable: true,
    },
    {
      header: 'Source',
      name: 'source',
      path: 'source',
      filterable: true,
    },
    {
      header: 'Target',
      name: 'target',
      path: 'target',
      filterable: true,

    },
  ]


  pagination: Pagination = {
    pageSize: 30,
    currentPage: 1,
  };
  actionControls: ActionControl[] = [];

  constructor(
    private bsModalService: BsModalService,
    public mqttMappingService: MQTTMappingService,
    public alertservice: AlertService
  ) { }

  ngOnInit() {
    this.initMappingDetails();
  }


  private async initMappingDetails(): Promise<void> {
    this.mqttMappings = await this.mqttMappingService.loadMappings();
    if (!this.mqttMappings) {
      return;
    }
  }

  async onSaveButtonClicked() {
    this.saveMappings();
  }

  private async saveMappings() {
    const response = await this.mqttMappingService.saveMappings(this.mqttMappings);

    if (response.res.status === 201) {
      this.alertservice.success(gettext('Mappings saved successful'));
      this.isConnectionToMQTTEstablished = true;
    } else {
      this.alertservice.danger(gettext('Failed to save mappings'));
    }
  }

}
