import { Component } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';

@Component({
  template: `<span>{{context.item.value == 'AT_MOST_ONCE' ? 'At most once' : (context.item.value == 'AT_LEAST_ONCE' ? 'At least once': 'Exactly once' )}}</span>`
})
export class QOSRendererComponent {
  constructor(
    public context: CellRendererContext,
  ) {}
}