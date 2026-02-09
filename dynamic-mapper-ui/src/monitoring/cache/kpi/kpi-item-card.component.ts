import { Component, Input } from '@angular/core';
import { gettext } from '@c8y/ngx-components/gettext';
import { C8yTranslateModule, IconDirective } from '@c8y/ngx-components';
import { CommonModule } from '@angular/common';
import { KpiDetails } from '../../shared/monitoring.service';

const DataType = {
  entries: 'entries'
} as const;

@Component({
  selector: 'd11r-kpi-item-card',
  standalone: true,
  imports: [IconDirective, C8yTranslateModule, CommonModule,],
  templateUrl: './kpi-item-card.component.html',
  host: { class: 'card m-b-0 fit-w fit-h' }
})
export class KpiItemCardComponent {
  /**
   * The label of the service (already translated).
   */
  @Input() kpi: KpiDetails;
}