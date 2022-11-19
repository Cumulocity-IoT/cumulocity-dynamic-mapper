

import { NgModule } from '@angular/core';
import { CoreModule, HOOK_ROUTE, Route } from '@c8y/ngx-components';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { MappingComponent } from './grid/mapping.component';
import { MappingTypeComponent } from './mapping-type/mapping-type.component';
import { OverwriteDeviceIdentifierModalComponent } from './overwrite/overwrite-device-identifier-modal.component';
import { OverwriteSubstitutionModalComponent } from './overwrite/overwrite-substitution-modal.component';
import { APIRendererComponent } from './renderer/api.renderer.component';
import { QOSRendererComponent } from './renderer/qos-cell.renderer.component';
import { SnoopedTemplateRendererComponent } from './renderer/snoopedTemplate.renderer.component';
import { StatusRendererComponent } from './renderer/status-cell.renderer.component';
import { TemplateRendererComponent } from './renderer/template.renderer.component';
import { SnoopingModalComponent } from './snooping/snooping-modal.component';
import { MappingStepperComponent } from './stepper/mapping-stepper.component';
import { SubstitutionRendererComponent } from './stepper/substitution/substitution-renderer.component';
import { SharedModule } from '../shared/shared.module';
import { ConfigurationModule } from '../mqtt-configuration/configuration.module';

@NgModule({
  declarations: [
    MappingComponent,
    MappingStepperComponent,
    OverwriteSubstitutionModalComponent,
    OverwriteDeviceIdentifierModalComponent,
    StatusRendererComponent,
    QOSRendererComponent,
    TemplateRendererComponent,
    SnoopedTemplateRendererComponent,
    SubstitutionRendererComponent,
    APIRendererComponent,
    SnoopingModalComponent,
    MappingTypeComponent,
  ],
  imports: [
    CoreModule,
    SharedModule,
    PopoverModule,
    ConfigurationModule
  ],
  entryComponents: [
    OverwriteSubstitutionModalComponent,
    OverwriteDeviceIdentifierModalComponent,
    StatusRendererComponent,
    QOSRendererComponent,
    TemplateRendererComponent,
    SnoopedTemplateRendererComponent,
    SubstitutionRendererComponent,
    APIRendererComponent,
    SnoopingModalComponent,
    MappingTypeComponent,
  ],
  exports: [],
  providers: [
    {
      provide: HOOK_ROUTE,
      useValue: [
        {
          path: 'mqtt-mapping/mappings',
          component: MappingComponent,
        },
      ] as Route[],
      multi: true,
    },
  ]
})
export class MappingModule { }
