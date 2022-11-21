import { CommonModule } from '@angular/common';
import { NgModule } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import {
  CoreModule,
  HOOK_NAVIGATOR_NODES,
  HOOK_TABS,
  HOOK_WIZARD,
} from '@c8y/ngx-components';
import { ConfigurationModule } from './mqtt-configuration/configuration.module';
import { AddExtensionWizardComponent } from './mqtt-extension/add-extension-wizard.component';
import { ExtensionModule } from './mqtt-extension/extension.module';
import { MappingTreeModule } from './mqtt-mapping-tree/tree.module';
import { MappingTypeComponent } from './mqtt-mapping/mapping-type/mapping-type.component';
import { MappingModule } from './mqtt-mapping/mapping.module';
import { MonitoringModule } from './mqtt-monitoring/monitoring.module';
import { TestingModule } from './mqtt-testing-devices/testing.module';
import { MappingNavigationFactory } from './navigation.factory';
import { ServiceMappingComponent } from './service-mapping.component';
import { OverviewGuard } from './shared/overview.guard';
import { MappingTabFactory } from './tab.factory';

@NgModule({
  imports: [
    CoreModule,
    CommonModule,
    TestingModule,
    MappingModule,
    MappingTreeModule,
    MonitoringModule,
    ConfigurationModule,
    ExtensionModule,
    FormsModule,
    ReactiveFormsModule,
    RouterModule,
  ],
  exports: [
    ServiceMappingComponent,
  ],
  entryComponents: [ServiceMappingComponent],
  declarations: [
    ServiceMappingComponent
  ],
  providers: [
    OverviewGuard,
    { provide: HOOK_NAVIGATOR_NODES, useClass: MappingNavigationFactory, multi: true },
    { provide: HOOK_TABS, useClass: MappingTabFactory, multi: true },
    {
      provide: HOOK_WIZARD,
      useValue: {
        // The id of a wizard to which the entry should be hooked.
        wizardId: 'addMappingWizard',
        // The container component is responsible for handling subsequent steps in the wizard.
        component: MappingTypeComponent,
        // Menu entry name
        name: 'App mapping',
        // Menu entry icon
        c8yIcon: 'plus-circle'
      },
      multi: true
    },
    {
      provide: HOOK_WIZARD,
      useValue: {
        wizardId: 'uploadExtensionWizard',
        component: AddExtensionWizardComponent,
        name: 'Upload Extension',
        c8yIcon: 'upload'
      },
      multi: true
    },
  ],
})
export class MQTTMappingModule {
  constructor() { }
}