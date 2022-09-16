import { CommonModule } from '@angular/common';
import { APP_INITIALIZER, NgModule } from '@angular/core';
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
import { TerminateBrokerConnectionModalComponent } from './mqtt-configuration/terminate-connection-modal/terminate-connection-modal.component';
import { MQTTMappingStepperComponent } from './mqtt-mapping/mqtt-mapping-stepper.component';
import { MQTTMappingComponent } from './mqtt-mapping/mqtt-mapping.component';
import { MQTTMappingService } from './mqtt-mapping/mqtt-mapping.service';
import { OverwriteSubstitutionModalComponent } from './mqtt-mapping/overwrite-substitution-modal/overwrite-substitution-modal.component';
import { QOSRendererComponent } from './mqtt-mapping/qos-cell.renderer.component';
import { SnoopedTemplateRendererComponent } from './mqtt-mapping/snoopedTemplate.renderer.component';
import { StatusRendererComponent } from './mqtt-mapping/status-cell.renderer.component';
import { SubstitutionRendererComponent } from './mqtt-mapping/substitution/substitution-renderer.component';
import { TemplateRendererComponent } from './mqtt-mapping/template.renderer.component';
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
    StatusRendererComponent,
    QOSRendererComponent,
    TemplateRendererComponent,
    SnoopedTemplateRendererComponent,
    SubstitutionRendererComponent,
  ],
  entryComponents: [
    MQTTServiceConfigurationComponent,
    MQTTConfigurationComponent,
    MQTTMappingComponent,
    MQTTMappingStepperComponent,
    TerminateBrokerConnectionModalComponent,
    OverwriteSubstitutionModalComponent,
    StatusRendererComponent,
    QOSRendererComponent,
    TemplateRendererComponent,
    SnoopedTemplateRendererComponent,
    SubstitutionRendererComponent,
  ],
  declarations: [
    MQTTServiceConfigurationComponent,
    MQTTConfigurationComponent,
    MQTTMappingComponent,
    MQTTMappingStepperComponent,
    TerminateBrokerConnectionModalComponent,
    OverwriteSubstitutionModalComponent,
    StatusRendererComponent,
    QOSRendererComponent,
    TemplateRendererComponent,
    SnoopedTemplateRendererComponent,
    SubstitutionRendererComponent
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