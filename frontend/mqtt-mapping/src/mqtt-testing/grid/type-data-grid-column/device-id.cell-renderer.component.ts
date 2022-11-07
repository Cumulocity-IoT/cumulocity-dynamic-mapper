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
      <a href="{{'/apps/devicemanagement/index.html#/device/' + context.item.id}}" title = "{{context.item.id}}" target="_blank" class="text-primary">{{context.item.id}}</a>
    </span>
  `
})
export class DeviceIdCellRendererComponent {
  constructor(
    public context: CellRendererContext,
    @Inject(TestingDeviceService) public service: TestingDeviceService
  ) {
    console.log("HHHH", context.item)
  }
}
