import { Component } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';

@Component({
  templateUrl: './snoopedTemplate.renderer.component.html'
})
export class SnoopedTemplateRendererComponent {
  constructor(
    public context: CellRendererContext,
  ) {}
}