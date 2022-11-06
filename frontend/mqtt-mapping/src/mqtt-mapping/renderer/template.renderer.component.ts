import { Component } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';

@Component({
  template: `
    <textarea class="text-monospace font-smaller" style="border:none; background-color: transparent;" rows="3" cols="40" title="{{json}}">{{json}}</textarea>
    `
})
export class TemplateRendererComponent {
  public json: string;
  constructor(
    public context: CellRendererContext,
  ) {
    this.json = JSON.stringify(JSON.parse(context.value), null, 4);
  }
}