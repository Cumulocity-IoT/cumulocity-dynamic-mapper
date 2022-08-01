import { NgModule } from '@angular/core';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { RouterModule as ngRouterModule } from '@angular/router';
import { BootstrapComponent, CoreModule, RouterModule } from '@c8y/ngx-components';
import { BsModalRef } from 'ngx-bootstrap/modal';
//import { MonacoEditorModule, MONACO_PATH } from '@materia-ui/ngx-monaco-editor';
// generates error
// TypeError: t.valueAccessor is null


@NgModule({
  imports: [
    BrowserAnimationsModule,
    ngRouterModule.forRoot([], { enableTracing: false, useHash: true }),
    RouterModule.forRoot(),
 //   MonacoEditorModule,
    CoreModule.forRoot(),
  ],
  providers: [BsModalRef,
    // {
    //   provide: MONACO_PATH,
    //   useValue: 'https://unpkg.com/monaco-editor@0.24.0/min/vs'
    // }
  ],
  bootstrap: [BootstrapComponent]
})
export class AppModule {}
