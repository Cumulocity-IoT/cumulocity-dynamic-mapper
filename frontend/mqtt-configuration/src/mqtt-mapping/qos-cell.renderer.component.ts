import { Component } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';

@Component({
  templateUrl: './qos-cell.renderer.component.html'
})
export class QOSRendererComponent {
  constructor(
    public context: CellRendererContext,
  ) {}
}