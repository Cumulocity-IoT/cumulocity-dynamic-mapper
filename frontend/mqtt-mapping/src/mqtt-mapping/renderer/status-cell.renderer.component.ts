import { Component } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';
import { SnoopStatus } from 'src/shared/configuration.model';

@Component({
  template: `<div class="c8y-realtime" title="Active">
  <span class="c8y-pulse animated pulse" [ngClass]="{
      active: context.item.active,
      inactive: !context.item.active
    }"></span>
</div>
<div class="c8y-realtime" title="Tested">
  <span class="c8y-pulse animated pulse" [ngClass]="{
      active: context.item.tested,
      inactive: !context.item.tested
    }" ></span>
</div>
<div class="c8y-realtime" title="Snooping">
  <span class="c8y-pulse animated pulse" [ngClass]="{
      active: context.item.snoopStatus == 'ENABLED' || context.item.snoopStatus == 'STARTED',
      inactive: context.item.snoopStatus == 'NONE' || context.item.snoopStatus == 'STOPPED'
    }"></span>
</div>
`
})
export class StatusRendererComponent {
  constructor(
    public context: CellRendererContext,
  ) { }
  SnoopStatus: SnoopStatus;
}