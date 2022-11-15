

import { NgModule } from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';
import { AddProcessorExtensionComponent } from './add-processor-extension.component';
import { AddExtensionComponent } from './add-processor.component';
import { ProcessorCardComponent } from './processor-card.component';
import { ProcessorComponent } from './processor.component';
import { BsDropdownModule } from 'ngx-bootstrap/dropdown';

@NgModule({
  declarations: [
    ProcessorComponent,
    ProcessorCardComponent,
    AddExtensionComponent,
    AddProcessorExtensionComponent,
  ],
  imports: [
    CoreModule,
    BsDropdownModule.forRoot()
  ],
  entryComponents: [
    ProcessorComponent,
    ProcessorCardComponent,
  ],
  exports: [],
  providers: []
})
export class ProcessorModule {}
