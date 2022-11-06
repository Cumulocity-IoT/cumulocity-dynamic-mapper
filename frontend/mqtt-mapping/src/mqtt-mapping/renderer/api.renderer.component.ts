import { Component } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';

@Component({
  template: `
    <span title="{{context.value}}">{{(context.value?.length > 0 ? context.value.charAt(0) : 0 )}}</span>
    `
})
export class APIRendererComponent {
  constructor(
    public context: CellRendererContext,
  ) {
    //console.log("Context:", context.item, context)
  }
}