

import { NgModule } from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';
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
  providers: []
})
export class TestingModule {}
