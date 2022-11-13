

import { NgModule } from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';
import { BokerConfigurationComponent } from './broker-configuration.component';
import { ProcessorModule } from './extension/processor.module';
import { TerminateBrokerConnectionModalComponent } from './terminate/terminate-connection-modal.component';

@NgModule({
  declarations: [
    BokerConfigurationComponent,
    TerminateBrokerConnectionModalComponent,
  ],
  imports: [
    CoreModule,
    ProcessorModule
  ],
  entryComponents: [
    TerminateBrokerConnectionModalComponent
  ],
  exports: [],
  providers: []
})
export class ConfigurationModule {}
