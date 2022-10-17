import { Component } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';
import { SnoopStatus } from 'src/shared/configuration.model';

@Component({
  templateUrl: './status-cell.renderer.component.html',
})
export class StatusRendererComponent {
  constructor(
    public context: CellRendererContext,
  ) { }
  SnoopStatus: SnoopStatus;
}