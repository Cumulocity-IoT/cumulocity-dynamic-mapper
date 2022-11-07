

import { NgModule } from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';
import { BokerConfigurationComponent } from './broker-configuration.component';
import { BrokerConfigurationService } from './broker-configuration.service';
import { TerminateBrokerConnectionModalComponent } from './terminate/terminate-connection-modal.component';


@NgModule({
  declarations: [
    BokerConfigurationComponent,
    TerminateBrokerConnectionModalComponent,
  ],
  imports: [
    CoreModule,
  ],
  entryComponents: [
    TerminateBrokerConnectionModalComponent
  ],
  exports: [],
  providers: [BrokerConfigurationService]
})
export class ConfigurationModule {}
