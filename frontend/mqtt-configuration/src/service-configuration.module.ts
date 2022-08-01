import { NgModule } from '@angular/core';
import { RouterModule } from '@angular/router';
import {
  CoreModule,
  HOOK_NAVIGATOR_NODES,
  HOOK_ROUTE,
  HOOK_TABS,
  Route,
} from '@c8y/ngx-components';
import { MQTTConfigurationNavigationFactory } from './navigation.factory';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { MQTTServiceConfigurationComponent } from './service-configuration.component';
import { MQTTConfigurationComponent } from './mqtt-configuration/mqtt-configuration.component';
import { MQTTConfigurationTabFactory } from './tab.factory';
import { MQTTTerminateConnectionModalComponent } from './mqtt-configuration/terminate-connection-modal/terminate-connection-modal.component'
import { CommonModule } from '@angular/common';
import { MQTTOverviewGuard } from './shared/mqtt-overview.guard';
import { MQTTMappingComponent } from './mqtt-mapping/mqtt-mapping.component';
import { MonacoEditorModule, MONACO_PATH } from '@materia-ui/ngx-monaco-editor';



@NgModule({
  imports: [
    CoreModule,
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    MonacoEditorModule,

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
    MQTTTerminateConnectionModalComponent,
  ],
  entryComponents: [
    MQTTServiceConfigurationComponent,
    MQTTConfigurationComponent,
    MQTTMappingComponent,
    MQTTTerminateConnectionModalComponent,
  ],
  declarations: [
    MQTTServiceConfigurationComponent,
    MQTTConfigurationComponent,
    MQTTMappingComponent,
    MQTTTerminateConnectionModalComponent,
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
      ] as Route[],
      multi: true,
    },
    {
      provide: MONACO_PATH,
      useValue: 'https://unpkg.com/monaco-editor@0.24.0/min/vs'
    }
  ],
})
export class MQTTServiceConfigurationModule {}
