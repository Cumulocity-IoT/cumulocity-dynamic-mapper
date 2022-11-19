

import { NgModule } from '@angular/core';
import { CoreModule, HOOK_ROUTE, Route } from '@c8y/ngx-components';
import { TestingComponent } from './grid/testing.component';
import { TypeCellRendererComponent } from './grid/type-data-grid-column/type.cell-renderer.component';
import { TypeFilteringFormRendererComponent } from './grid/type-data-grid-column/type.filtering-form-renderer.component';
import { TypeHeaderCellRendererComponent } from './grid/type-data-grid-column/type.header-cell-renderer.component';

@NgModule({
  declarations: [
    TestingComponent,
    TypeHeaderCellRendererComponent,
    TypeCellRendererComponent,
    TypeFilteringFormRendererComponent
  ],
  imports: [
    CoreModule,
  ],
  entryComponents: [
    TypeHeaderCellRendererComponent,
    TypeCellRendererComponent,
    TypeFilteringFormRendererComponent
  ],
  exports: [], 
  providers: [
    {
      provide: HOOK_ROUTE,
      useValue: [
        {
          path: 'mqtt-mapping/testing',
          component: TestingComponent,
        }
      ] as Route[],
      multi: true,
    },
  ]
})
export class TestingModule {}
