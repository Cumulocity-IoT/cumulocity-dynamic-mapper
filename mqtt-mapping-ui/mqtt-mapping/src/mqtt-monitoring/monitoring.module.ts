

import { NgModule } from '@angular/core';
import { CoreModule, HOOK_ROUTE, Route } from '@c8y/ngx-components';
import { MonitoringComponent } from './grid/monitoring.component';
import { IdRendererComponent } from './renderer/id-cell.renderer.component';

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
  providers: [
    {
      provide: HOOK_ROUTE,
      useValue: [
        {
          path: 'mqtt-mapping/monitoring',
          component: MonitoringComponent,
        },
      ] as Route[],
      multi: true,
    },
  ]
})
export class MonitoringModule {}
