import { ApplicationConfig, importProvidersFrom } from '@angular/core';
import { provideAnimations } from '@angular/platform-browser/animations';
import { CoreModule, RouterModule, VersionModule } from '@c8y/ngx-components';
import { BulkOperationSchedulerModule } from '@c8y/ngx-components/operations/bulk-operation-scheduler';
import { DynamicMappingModule } from './dynamic-mapping.module';

export const appConfig: ApplicationConfig = {
  providers: [
    provideAnimations(),
    importProvidersFrom(RouterModule.forRoot()),
    importProvidersFrom(DynamicMappingModule),
    importProvidersFrom(CoreModule.forRoot()),
    importProvidersFrom(BulkOperationSchedulerModule),
    // Get rid of a default version factory
    importProvidersFrom(VersionModule.config({ disableWebSDKPluginVersionFactory: true })),
  ]
};
