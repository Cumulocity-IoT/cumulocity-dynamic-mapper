import { Component, OnInit, ViewEncapsulation } from '@angular/core';
import { ActionControl, AlertService, Column, ColumnDataType, DisplayOptions, Pagination } from '@c8y/ngx-components';
import { Observable } from 'rxjs';
import { MappingStatus } from '../../shared/configuration.model';
import { IdRendererComponent } from '../renderer/id-cell.renderer.component';
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
      gridTrackSize: '10%',
      cellRendererComponent: IdRendererComponent
    },
    {
      name: 'subscriptionTopic',
      header: 'Subscription Topic',
      path: 'subscriptionTopic',
      filterable: false,
      dataType: ColumnDataType.TextLong,
      gridTrackSize: '20%',
    },
    {
      header: '# Errors',
      name: 'errors',
      path: 'errors',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      gridTrackSize: '17.5%'
    },
    {
      header: '# Messages Received',
      name: 'messagesReceived',
      path: 'messagesReceived',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      gridTrackSize: '17.5%'
    },
    {
      header: '# Snooped Templates Total',
      name: 'snoopedTemplatesTotal',
      path: 'snoopedTemplatesTotal',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      gridTrackSize: '17.5%'
    },
    {
      header: '# Snooped Templates Active',
      name: 'snoopedTemplatesActive',
      path: 'snoopedTemplatesActive',
      filterable: true,
      dataType: ColumnDataType.Numeric,
      gridTrackSize: '17.5%'
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
    this.subscription = await this.monitoringService.subscribeMonitoringChannel();
    this.monitorings$ = this.monitoringService.getCurrentMappingStatus();
  }

  ngOnDestroy(): void {
    console.log("Stop subscription");
    this.monitoringService.unsubscribeFromMonitoringChannel(this.subscription);
  }

}
