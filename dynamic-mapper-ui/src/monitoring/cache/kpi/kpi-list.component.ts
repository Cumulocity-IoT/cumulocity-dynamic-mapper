import { Component, inject, OnInit } from '@angular/core';
import {
  ActionBarItemComponent,
  AlertService,
  BreadcrumbModule,
  C8yTranslateModule,
  HeaderModule,
  HelpModule,
  IconDirective,
  LoadingComponent
} from '@c8y/ngx-components';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { KpiItemComponent } from './kpi-item.component';
import { MonitoringService, KpiDetails } from '../../shared/monitoring.service';

@Component({
  selector: 'd11r-kpi-list',
  templateUrl: './kpi-list.component.html',
  imports: [
    CommonModule,
    HeaderModule,
    HelpModule,
    C8yTranslateModule,
    KpiItemComponent,
    RouterLink,
    BreadcrumbModule,
    ActionBarItemComponent,
    IconDirective,
    LoadingComponent
  ],
  standalone: true
})
export class KpListComponent implements OnInit {
  alertService = inject(AlertService);
  monitoringService = inject(MonitoringService);

  kpisDetails: KpiDetails[] = [];
  loading = true;

  async ngOnInit() {
    await this.reload();
  }

  async reload() {
    this.loading = true;
    try {
      // load KPI details from monitoring service
      this.kpisDetails = await this.monitoringService.getKpisDetails();
    } catch (e) {
      this.alertService.addServerFailure(e);
    } finally {
      this.loading = false;
    }
  }
}