import { Component, OnInit, ViewEncapsulation } from '@angular/core';
import { ActionControl, AlertService, Column, ColumnDataType, DataGridComponent, DisplayOptions, gettext, Pagination } from '@c8y/ngx-components';
import { Observable } from 'rxjs';
import { MappingStatus } from '../../shared/configuration.model';
import { MonitoringService } from '../shared/monitoring.service';

@Component({
  selector: 'monitoring-grid',
  templateUrl: 'monitoring.component.html',
  styleUrls: ['../../mqtt-mapping/shared/mapping.style.css',
    '../../../node_modules/jsoneditor/dist/jsoneditor.min.css'],
  encapsulation: ViewEncapsulation.None,
})

export class MonitoringComponent implements OnInit {

  monitorings$: Observable<MappingStatus[]>;
  subscription: object;

  displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: false,
    gridHeader: true
  };

  columns: Column[] = [
    {
      name: 'id',
      header: 'Mapping Id',
      path: 'id',
      filterable: false,
      dataType: ColumnDataType.TextShort,
      gridTrackSize: '10%'

    },
    {
      header: '# Errors',
      name: 'errors',
      path: 'errors',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      gridTrackSize: '30%'
    },
    {
      header: '# MessagesReceived',
      name: 'messagesReceived',
      path: 'messagesReceived',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      gridTrackSize: '30%'
    },
    {
      header: '# SnoopedTemplatesTotal',
      name: 'snoopedTemplatesTotal',
      path: 'snoopedTemplatesTotal',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      gridTrackSize: '30%'
    },

  ]

  pagination: Pagination = {
    pageSize: 3,
    currentPage: 1,
  };

  actionControls: ActionControl[] = [];

  constructor(
    public monitoringService: MonitoringService,
    public alertService: AlertService
  ) { }

  ngOnInit() {
    this.initializeMonitoringService();
  }

  private async initializeMonitoringService() {
    this.subscription = await this.monitoringService.subscribeToMonitoringChannel();
    this.monitorings$ = this.monitoringService.getCurrentMonitoringDetails();
  }

  ngOnDestroy(): void {
    console.log("Stop subscription");
    this.monitoringService.unsubscribeFromMonitoringChannel(this.subscription);
  }

}
