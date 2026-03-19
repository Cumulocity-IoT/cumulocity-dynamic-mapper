
import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { C8yTranslatePipe, IconDirective } from '@c8y/ngx-components';
import { KpiItemCardComponent } from './kpi-item-card.component';
import { KpiDetails } from '../../shared/monitoring.service';

@Component({
  selector: 'd11r-kpi-item',
  standalone: true,
  imports: [CommonModule, KpiItemCardComponent],
  templateUrl: './kpi-item.component.html'
})
export class KpiItemComponent {
  @Input('kpi') kpi: KpiDetails;
}
