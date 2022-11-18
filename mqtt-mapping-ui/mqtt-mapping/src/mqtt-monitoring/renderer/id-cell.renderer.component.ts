import { Component } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';

@Component({
  template: `<span>{{context.item.id == -1 ? 'UNSPECIFIED' : context.item.id}}</span>`
})
export class IdRendererComponent {
  constructor(
    public context: CellRendererContext,
  ) {}
}