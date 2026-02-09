
import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { C8yTranslatePipe, IconDirective } from '@c8y/ngx-components';
import { KpiItemCardComponent } from './kpi-item-card.component';
import { KpiDetails } from '../../shared/monitoring.service';

@Component({
  selector: 'd11r-kpi-item',
  standalone: true,
  imports: [CommonModule, IconDirective, KpiItemCardComponent, C8yTranslatePipe],
  templateUrl: './kpi-item.component.html'
})
export class KpiItemComponent {

  @Input('kpiName')
  set _kpiName(name: string) {
    this.kpiName = name;
    this.icon = 'bar-chart';
    // Map KPI IDs to dataType for KpiItemCardComponent
    this.dataType = this.getDataTypeFromKpiName(name);
  }
  kpiName = '';
  kpiLabel = '';
  icon = '';
  dataType: 'entries';

  @Input() 
  set kpi(value: KpiDetails) {
    this._kpi = value;
    if (value?.name) {
      this.kpiLabel = value.name;
    }
  }
  get kpi(): KpiDetails {
    return this._kpi;
  }
  private _kpi: KpiDetails = {
    id: '',
    name: '',
    itemName: ''
  };

  private getDataTypeFromKpiName(name: string):  'entries' {
    // Map cache IDs to data types
    const typeMap: Record<string, 'entries'> = {
      inventoryCache: 'entries',
      inboundIdCache: 'entries',
    };
    return typeMap[name] || 'entries';
  }
}
