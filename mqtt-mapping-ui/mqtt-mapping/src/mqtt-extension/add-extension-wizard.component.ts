import { Component } from '@angular/core';
import { IManagedObject, IManagedObjectBinary } from '@c8y/client';
import { gettext } from '@c8y/ngx-components';
import { ExtensionService } from './extension.service';

@Component({
    selector: 'c8y-add-extension-wizard',
    template: `<c8y-add-extension
      [headerText]="headerText"
      [headerIcon]="'upload'"
      [successText]="successText"
      [uploadExtensionHandler]="uploadExtensionHandler"
      [canGoBack]="true"
    ></c8y-add-extension>`
  })
  export class AddExtensionWizardComponent {
    headerText: string = gettext('Upload extension extension');
    successText: string = gettext('Extension created');
  
    constructor(private extensionService: ExtensionService) {}
  
    uploadExtensionHandler = (file: File, app: IManagedObject) => this.uploadExtension(file, app );
  
    async uploadExtension(file: File, app: IManagedObject): Promise<IManagedObjectBinary> {
      return this.extensionService.uploadExtension(file, app);
    }

  }