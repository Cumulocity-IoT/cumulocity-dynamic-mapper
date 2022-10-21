import { Component } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';
import { SnoopStatus } from 'src/shared/configuration.model';

@Component({
  templateUrl: './snoopedTemplate.renderer.component.html'
})
export class SnoopedTemplateRendererComponent {
  constructor(
    public context: CellRendererContext,
  ) {}

}