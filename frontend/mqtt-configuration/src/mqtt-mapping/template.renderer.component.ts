import { Component } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';

@Component({
  templateUrl: './template.renderer.component.html'
})
export class TemplateRendererComponent {
  public json: string;
  constructor(
    public context: CellRendererContext,
    ) {
      this.json  = JSON.stringify(JSON.parse(context.value), null, 4);
    }
  }