import { CommonModule } from '@angular/common';
import { NgModule } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import {
  CoreModule,
  HOOK_NAVIGATOR_NODES,
  HOOK_ROUTE,
  HOOK_TABS,
  Route
} from '@c8y/ngx-components';
import { MQTTConfigurationComponent } from './mqtt-configuration/mqtt-configuration.component';
import { MQTTConfigurationService } from './mqtt-configuration/mqtt-configuration.service';
import { MQTTTerminateConnectionModalComponent } from './mqtt-configuration/terminate-connection-modal/terminate-connection-modal.component';
import { MQTTMappingStepperComponent } from './mqtt-mapping/mqtt-mapping-stepper.component';
import { MQTTMappingComponent } from './mqtt-mapping/mqtt-mapping.component';
import { MQTTMappingService } from './mqtt-mapping/mqtt-mapping.service';
import { QOSRendererComponent } from './mqtt-mapping/qos-cell.renderer.component';
import { StatusRendererComponent } from './mqtt-mapping/status-cell.renderer.component';
import { MQTTConfigurationNavigationFactory } from './navigation.factory';
import { MQTTServiceConfigurationComponent } from './service-configuration.component';
import { MqttTestingComponent } from './mqtt-testing/mqtt-testing.component';
import { MQTTOverviewGuard } from './shared/mqtt-overview.guard';
import { MQTTConfigurationTabFactory } from './tab.factory';
import { NgJsonEditorModule } from '@maaxgr/ang-jsoneditor'

@NgModule({
  imports: [
    CoreModule,
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    NgJsonEditorModule,
    RouterModule.forChild([
      {
        path: 'mqtt/configuration',
        pathMatch: 'full',
        component: MQTTConfigurationComponent,
      },
      {
        path: 'mqtt/mapping',
        pathMatch: 'full',
        component: MQTTMappingComponent,
      },
    ]),
  ],
  exports: [
    MQTTServiceConfigurationComponent,
    MQTTConfigurationComponent,
    MQTTMappingComponent,
    MQTTMappingStepperComponent,
    MQTTTerminateConnectionModalComponent,
    MqttTestingComponent,
    StatusRendererComponent,
    QOSRendererComponent,
  ],
  entryComponents: [
    MQTTServiceConfigurationComponent,
    MQTTConfigurationComponent,
    MQTTMappingComponent,
    MQTTMappingStepperComponent,
    MQTTTerminateConnectionModalComponent,
    MqttTestingComponent,
    StatusRendererComponent,
    QOSRendererComponent
  ],
  declarations: [
    MQTTServiceConfigurationComponent,
    MQTTConfigurationComponent,
    MQTTMappingComponent,
    MQTTMappingStepperComponent,
    MQTTTerminateConnectionModalComponent,
    MqttTestingComponent,
    StatusRendererComponent,
    QOSRendererComponent
  ],
  providers: [
    MQTTOverviewGuard,
    { provide: HOOK_NAVIGATOR_NODES, useClass: MQTTConfigurationNavigationFactory, multi: true },
    { provide: HOOK_TABS, useClass: MQTTConfigurationTabFactory, multi: true },
    {
      provide: HOOK_ROUTE,
      useValue: [
        {
          path: 'mqtt/configuration',
          component: MQTTConfigurationComponent,
        },
        {
          path: 'mqtt/mapping',
          component: MQTTMappingComponent,
        },
        {
          path: 'mqtt/testing',
          component: MqttTestingComponent,
        },
      ] as Route[],
      multi: true,
    },
  ],
})
export class MQTTServiceConfigurationModule {
  constructor(
    private mapping: MQTTMappingService,
    private config: MQTTConfigurationService
  ) {
    this.mapping.initializeMQTTAgent().then(  (agent) => {
      console.log("Found MQTTAgent in mapping:", agent);
    });
    this.config.initializeMQTTAgent().then();
  }
}
