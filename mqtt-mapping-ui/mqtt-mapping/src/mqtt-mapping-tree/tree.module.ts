import { NgModule } from '@angular/core';
import { CoreModule, HOOK_ROUTE, Route } from '@c8y/ngx-components';
import { MappingTreeComponent } from './tree.component';
import { SharedModule } from '../shared/shared.module';

@NgModule({
  declarations: [
    MappingTreeComponent
  ],
  imports: [
    CoreModule,
    SharedModule,
  ],
  entryComponents: [
    MappingTreeComponent,
  ],
  exports: [],
  providers: [
    {
      provide: HOOK_ROUTE,
      useValue: [
        {
          path: 'mqtt-mapping/tree',
          component: MappingTreeComponent,
        }
      ] as Route[],
      multi: true,
    },
  ]
})
export class MappingTreeModule {}
