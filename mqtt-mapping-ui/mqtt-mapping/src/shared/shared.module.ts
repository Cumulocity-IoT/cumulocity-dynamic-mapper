import { NgModule } from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';
import { JsonEditorComponent } from './editor/jsoneditor.component';

@NgModule({
  declarations: [
    JsonEditorComponent,
  ],
  imports: [
    CoreModule,
  ],
  entryComponents: [
    JsonEditorComponent,
  ],
  exports: [JsonEditorComponent],
  providers: []
})
export class SharedModule {}
