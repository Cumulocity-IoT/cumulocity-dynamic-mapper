import { Component } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';

@Component({
  templateUrl: './id-cell.renderer.component.html'
})
export class IdRendererComponent {
  constructor(
    public context: CellRendererContext,
  ) {}
}