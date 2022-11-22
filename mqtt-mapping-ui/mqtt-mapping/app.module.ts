import { NgModule } from '@angular/core';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { RouterModule as ngRouterModule } from '@angular/router';
import { BootstrapComponent, CoreModule, RouterModule } from '@c8y/ngx-components';
import { BsModalRef } from 'ngx-bootstrap/modal';
import { MQTTMappingModule } from './src/service-mapping.module';


@NgModule({
  imports: [
    BrowserAnimationsModule,
    ngRouterModule.forRoot([], { enableTracing: true, useHash: true }),
    RouterModule.forRoot(),
    CoreModule.forRoot(),
    MQTTMappingModule
  ],
  providers: [BsModalRef,
  ],
  bootstrap: [BootstrapComponent]
})
export class AppModule {}
