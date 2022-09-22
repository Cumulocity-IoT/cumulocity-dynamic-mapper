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
import { BokerConfigurationComponent } from './mqtt-configuration/broker-configuration.component';
import { BrokerConfigurationService } from './mqtt-configuration/broker-configuration.service';
import { TerminateBrokerConnectionModalComponent } from './mqtt-configuration/terminate/terminate-connection-modal.component';
import { MappingComponent } from './mqtt-mapping/grid/mapping.component';
import { OverwriteDeviceIdentifierModalComponent } from './mqtt-mapping/overwrite/overwrite-device-identifier-modal.component';
import { OverwriteSubstitutionModalComponent } from './mqtt-mapping/overwrite/overwrite-substitution-modal.component';
import { APIRendererComponent } from './mqtt-mapping/renderer/api.renderer.component';
import { QOSRendererComponent } from './mqtt-mapping/renderer/qos-cell.renderer.component';
import { SnoopedTemplateRendererComponent } from './mqtt-mapping/renderer/snoopedTemplate.renderer.component';
import { StatusRendererComponent } from './mqtt-mapping/renderer/status-cell.renderer.component';
import { TemplateRendererComponent } from './mqtt-mapping/renderer/template.renderer.component';
import { MappingService } from './mqtt-mapping/shared/mapping.service';
import { MappingStepperComponent } from './mqtt-mapping/stepper/mapping-stepper.component';
import { SubstitutionRendererComponent } from './mqtt-mapping/stepper/substitution/substitution-renderer.component';
import { MonitoringComponent } from './mqtt-monitoring/grid/monitoring.component';
import { IdRendererComponent } from './mqtt-monitoring/renderer/id-cell.renderer.component';
import { MonitoringService } from './mqtt-monitoring/shared/monitoring.service';
import { ConfigurationNavigationFactory } from './navigation.factory';
import { ServiceMappingComponent } from './service-mapping.component';
import { OverviewGuard } from './shared/overview.guard';
import { ConfigurationTabFactory } from './tab.factory';

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
        component: BokerConfigurationComponent,
      },
      {
        path: 'mqtt/mapping',
        pathMatch: 'full',
        component: MappingComponent,
      },
      {
        path: 'mqtt/monitoring',
        pathMatch: 'full',
        component: MonitoringComponent,
      },
    ]),
  ],
  exports: [
    ServiceMappingComponent,
    BokerConfigurationComponent,
    MappingComponent,
    MappingStepperComponent,
    TerminateBrokerConnectionModalComponent,
    OverwriteSubstitutionModalComponent,
    OverwriteDeviceIdentifierModalComponent,
    StatusRendererComponent,
    QOSRendererComponent,
    TemplateRendererComponent,
    SnoopedTemplateRendererComponent,
    SubstitutionRendererComponent,
    APIRendererComponent,
    MonitoringComponent,
    IdRendererComponent,
  ],
  entryComponents: [
    ServiceMappingComponent,
    BokerConfigurationComponent,
    MappingComponent,
    MappingStepperComponent,
    TerminateBrokerConnectionModalComponent,
    OverwriteSubstitutionModalComponent,
    OverwriteDeviceIdentifierModalComponent,
    StatusRendererComponent,
    QOSRendererComponent,
    TemplateRendererComponent,
    SnoopedTemplateRendererComponent,
    SubstitutionRendererComponent,
    APIRendererComponent,
    MonitoringComponent,
    IdRendererComponent,
  ],
  declarations: [
    ServiceMappingComponent,
    BokerConfigurationComponent,
    MappingComponent,
    MappingStepperComponent,
    TerminateBrokerConnectionModalComponent,
    OverwriteSubstitutionModalComponent,
    OverwriteDeviceIdentifierModalComponent,
    StatusRendererComponent,
    QOSRendererComponent,
    TemplateRendererComponent,
    SnoopedTemplateRendererComponent,
    SubstitutionRendererComponent,
    APIRendererComponent,
    MonitoringComponent,
    IdRendererComponent,
  ],
  providers: [
    OverviewGuard,
    BrokerConfigurationService,
    MonitoringService,
    MappingService,
    { provide: HOOK_NAVIGATOR_NODES, useClass: ConfigurationNavigationFactory, multi: true },
    { provide: HOOK_TABS, useClass: ConfigurationTabFactory, multi: true },
    {
      provide: HOOK_ROUTE,
      useValue: [
        {
          path: 'mqtt/configuration',
          component: BokerConfigurationComponent,
        },
        {
          path: 'mqtt/mapping',
          component: MappingComponent,
        },
        {
          path: 'mqtt/monitoring',
          component: MonitoringComponent,
        },
      ] as Route[],
      multi: true,
    },
  ],
})
export class MQTTMappingModule {
  constructor() {}
}