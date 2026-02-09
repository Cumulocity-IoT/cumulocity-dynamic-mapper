import { Component, Input } from '@angular/core';
import { gettext } from '@c8y/ngx-components/gettext';
import { C8yTranslateModule, IconDirective } from '@c8y/ngx-components';
import { CommonModule } from '@angular/common';

const DataType = {
  entries: 'entries'
} as const;

@Component({
  selector: 'd11r-kpi-item-card',
  standalone: true,
  imports: [IconDirective, C8yTranslateModule, CommonModule,],
  templateUrl: './kpi-item-card.component.html',
  host: { class: 'card m-b-0 fit-w' }
})
export class KpiItemCardComponent {
  readonly DATA_TYPE = DataType;
  readonly ITEM_DETAILS: Record<keyof typeof DataType, { icon: string; title: string }> = {
    entries: { icon: 'day-view', title: gettext('Entries') }
  } as const;

  /**
   * The label of the service (already translated).
   */
  @Input() serviceLabel = '';
  @Input() limit?: number;
  @Input() dataType: keyof typeof DataType = 'entries';
  @Input() value: number | undefined;
}