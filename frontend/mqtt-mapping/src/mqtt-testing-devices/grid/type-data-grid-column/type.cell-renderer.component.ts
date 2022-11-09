import { Component, Inject } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';
import { TestingDeviceService } from '../testing.service';

/**
 * The example component for custom cell renderer.
 * It gets `context` with the current row item and the column.
 * Additionally, a service is injected to provide a helper method.
 * The template displays the icon and the label with additional styling.
 */
@Component({
  template: `
    <span>
      <i [c8yIcon]="value.icon" class="m-r-5"></i>
      <code>{{ value.label }}</code>
    </span>
  `
})
export class TypeCellRendererComponent {
  /** Returns the icon and label for the current item. */
  get value() {
    return this.service.getTypeIconAndLabel(this.context.item);
  }

  constructor(
    public context: CellRendererContext,
    @Inject(TestingDeviceService) public service: TestingDeviceService
  ) {}
}
