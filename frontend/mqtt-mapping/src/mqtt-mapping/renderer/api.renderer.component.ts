import { Component } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';

@Component({
  templateUrl: './api.renderer.component.html'
})
export class APIRendererComponent {
  constructor(
    public context: CellRendererContext,
  ) {
    //console.log("Context:", context.item, context)
  }
}