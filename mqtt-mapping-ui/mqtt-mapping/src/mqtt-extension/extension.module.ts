

import { NgModule } from '@angular/core';
import { CoreModule, gettext, HOOK_ROUTE, Route } from '@c8y/ngx-components';
import { AddExtensionComponent } from './add-extension.component';
import { AddExtensionWizardComponent } from './add-extension-wizard.component';
import { ExtensionCardComponent } from './extension-card.component';
import { ExtensionComponent } from './extension.component';
import { BsDropdownModule } from 'ngx-bootstrap/dropdown';
import { ExtensionPropertiesComponent } from './extension-properties.component';
import { ExtensionGuard } from './extension.guard';
import { RouterModule } from '@angular/router';

const extensionRoutes: Route[] = [
  {
    path: 'mqtt-mapping/extensions',
    component: ExtensionComponent,
    pathMatch: "full",
    // children: [
    //   {
    //     // path: 'mqtt-mapping/extensions/properties/50051686',
    //     path: 'properties/:id',
    //     component: ExtensionPropertiesComponent,
    //   }
    // ]
    //canActivate: [ExtensionGuard],
  },
  {
    path: 'mqtt-mapping/extensions/properties/:id',
    component: ExtensionPropertiesComponent,
  }
];

@NgModule({
  declarations: [
    ExtensionComponent,
    ExtensionCardComponent,
    AddExtensionComponent,
    AddExtensionWizardComponent,
    ExtensionCardComponent,
    ExtensionPropertiesComponent,
  ],
  imports: [
    CoreModule,
    BsDropdownModule.forRoot(),
  ],
  entryComponents: [
    ExtensionComponent,
    ExtensionCardComponent,
    ExtensionPropertiesComponent
  ],
  exports: [],
  providers: [
    {
      provide: HOOK_ROUTE,
      useValue: extensionRoutes,
      multi: true
    },
  ]
})
export class ExtensionModule { }
