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
import { NgJsonEditorModule } from '@maaxgr/ang-jsoneditor';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { MQTTConfigurationComponent } from './mqtt-configuration/mqtt-configuration.component';
import { MQTTConfigurationService } from './mqtt-configuration/mqtt-configuration.service';
import { TerminateBrokerConnectionModalComponent } from './mqtt-configuration/terminate/terminate-connection-modal.component';
import { MQTTMappingComponent } from './mqtt-mapping/grid/mqtt-mapping.component';
import { OverwriteDeviceIdentifierModalComponent } from './mqtt-mapping/overwrite/overwrite-device-identifier-modal.component';
import { OverwriteSubstitutionModalComponent } from './mqtt-mapping/overwrite/overwrite-substitution-modal.component';
import { APIRendererComponent } from './mqtt-mapping/renderer/api.renderer.component';
import { QOSRendererComponent } from './mqtt-mapping/renderer/qos-cell.renderer.component';
import { SnoopedTemplateRendererComponent } from './mqtt-mapping/renderer/snoopedTemplate.renderer.component';
import { StatusRendererComponent } from './mqtt-mapping/renderer/status-cell.renderer.component';
import { TemplateRendererComponent } from './mqtt-mapping/renderer/template.renderer.component';
import { MQTTMappingService } from './mqtt-mapping/shared/mqtt-mapping.service';
import { MQTTMappingStepperComponent } from './mqtt-mapping/stepper/mqtt-mapping-stepper.component';
import { SubstitutionRendererComponent } from './mqtt-mapping/stepper/substitution/substitution-renderer.component';
import { MQTTConfigurationNavigationFactory } from './navigation.factory';
import { MQTTServiceConfigurationComponent } from './service-configuration.component';
import { MQTTOverviewGuard } from './shared/mqtt-overview.guard';
import { MQTTConfigurationTabFactory } from './tab.factory';

@NgModule({
  imports: [
    CoreModule,
    CommonModule,
    FormsModule,
    PopoverModule,
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
    TerminateBrokerConnectionModalComponent,
    OverwriteSubstitutionModalComponent,
    OverwriteDeviceIdentifierModalComponent,
    StatusRendererComponent,
    QOSRendererComponent,
    TemplateRendererComponent,
    SnoopedTemplateRendererComponent,
    SubstitutionRendererComponent,
    APIRendererComponent,
  ],
  entryComponents: [
    MQTTServiceConfigurationComponent,
    MQTTConfigurationComponent,
    MQTTMappingComponent,
    MQTTMappingStepperComponent,
    TerminateBrokerConnectionModalComponent,
    OverwriteSubstitutionModalComponent,
    OverwriteDeviceIdentifierModalComponent,
    StatusRendererComponent,
    QOSRendererComponent,
    TemplateRendererComponent,
    SnoopedTemplateRendererComponent,
    SubstitutionRendererComponent,
    APIRendererComponent,
  ],
  declarations: [
    MQTTServiceConfigurationComponent,
    MQTTConfigurationComponent,
    MQTTMappingComponent,
    MQTTMappingStepperComponent,
    TerminateBrokerConnectionModalComponent,
    OverwriteSubstitutionModalComponent,
    OverwriteDeviceIdentifierModalComponent,
    StatusRendererComponent,
    QOSRendererComponent,
    TemplateRendererComponent,
    SnoopedTemplateRendererComponent,
    SubstitutionRendererComponent,
    APIRendererComponent,
  ],
  providers: [
    MQTTOverviewGuard,
    MQTTConfigurationService,
    MQTTMappingService,
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
  ],
})
export class MQTTServiceConfigurationModule {
  constructor() {}
}