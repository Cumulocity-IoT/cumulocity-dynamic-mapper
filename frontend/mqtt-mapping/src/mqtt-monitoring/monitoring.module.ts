

import { NgModule } from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';
import { MonitoringComponent } from './grid/monitoring.component';
import { IdRendererComponent } from './renderer/id-cell.renderer.component';
import { MonitoringService } from './shared/monitoring.service';

@NgModule({
  declarations: [
    MonitoringComponent,
    IdRendererComponent,
  ],
  imports: [
    CoreModule,
  ],
  entryComponents: [
    IdRendererComponent,
  ],
  exports: [],
  providers: [MonitoringService]
})
export class MonitoringModule {}
