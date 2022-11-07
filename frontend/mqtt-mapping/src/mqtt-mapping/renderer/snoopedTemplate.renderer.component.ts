import { Component } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';
import { SnoopStatus } from 'src/shared/configuration.model';

@Component({
  template: `<span>{{context.item.snoopedTemplates.length}}</span>`
})
export class SnoopedTemplateRendererComponent {
  constructor(
    public context: CellRendererContext,
  ) {}

}