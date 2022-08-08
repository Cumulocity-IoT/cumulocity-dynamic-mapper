import { APP_INITIALIZER, NgModule } from '@angular/core';
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
import { MonacoEditorLoaderService, MonacoEditorModule } from '@materia-ui/ngx-monaco-editor';
import { StatusRendererComponent } from './mqtt-mapping/status-cell.renderer.component';
import { QOSRendererComponent } from './mqtt-mapping/qos-cell.renderer.component'
import { initWithDependencyFactory } from './mqtt-mapping/mqtt-mapping.service';
import { InventoryService } from '@c8y/client';
import { MQTTMappingStepperComponent } from './mqtt-mapping/mqtt-mapping-stepper.component';




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
    MQTTMappingStepperComponent,
    MQTTTerminateConnectionModalComponent,
    StatusRendererComponent,
    QOSRendererComponent,
  ],
  entryComponents: [
    MQTTServiceConfigurationComponent,
    MQTTConfigurationComponent,
    MQTTMappingComponent,
    MQTTMappingStepperComponent,
    MQTTTerminateConnectionModalComponent,
    StatusRendererComponent,
    QOSRendererComponent
  ],
  declarations: [
    MQTTServiceConfigurationComponent,
    MQTTConfigurationComponent,
    MQTTMappingComponent,
    MQTTMappingStepperComponent,
    MQTTTerminateConnectionModalComponent,
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
      ] as Route[],
      multi: true,
    },
    {
      provide: APP_INITIALIZER,
      useFactory: initWithDependencyFactory,
      deps: [InventoryService],
      multi: true,
    },
  ],
})
export class MQTTServiceConfigurationModule {
  constructor(
    private monaco: MonacoEditorLoaderService
  ) {
    this.monaco.monacoPath = '/apps/mqtt-configuration/assets/monaco-editor/min/vs';
    this.monaco.loadMonaco();
  }
}
