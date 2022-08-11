import { Component } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';

@Component({
  templateUrl: './status-cell.renderer.component.html',
  styleUrls: ['./jsoneditor.min.css']
})
export class StatusRendererComponent {
  constructor(
    public context: CellRendererContext,
  ) {}
}