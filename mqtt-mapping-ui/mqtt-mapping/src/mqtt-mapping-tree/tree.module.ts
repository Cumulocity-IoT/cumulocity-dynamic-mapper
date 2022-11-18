import { NgModule } from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';
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
  providers: []
})
export class MappingTreeModule {}
