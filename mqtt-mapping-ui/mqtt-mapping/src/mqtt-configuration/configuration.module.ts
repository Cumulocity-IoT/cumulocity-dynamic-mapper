

import { NgModule } from '@angular/core';
import { CoreModule, HOOK_ROUTE, Route } from '@c8y/ngx-components';
import { BokerConfigurationComponent } from './broker-configuration.component';
import { ExtensionModule } from '../mqtt-extension/extension.module';
import { TerminateBrokerConnectionModalComponent } from './terminate/terminate-connection-modal.component';

@NgModule({
  declarations: [
    BokerConfigurationComponent,
    TerminateBrokerConnectionModalComponent,
  ],
  imports: [
    CoreModule
  ],
  entryComponents: [
    TerminateBrokerConnectionModalComponent
  ],
  exports: [],
  providers: [
    {
      provide: HOOK_ROUTE,
      useValue: [
        {
          path: 'mqtt-mapping/configuration',
          component: BokerConfigurationComponent,
        },
      ] as Route[],
      multi: true,
    },
  ]
})
export class ConfigurationModule {}
