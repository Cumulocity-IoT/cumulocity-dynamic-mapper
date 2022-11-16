import { Component } from '@angular/core';
import { IManagedObject, IManagedObjectBinary } from '@c8y/client';
import { gettext } from '@c8y/ngx-components';
import { ProcessorService } from './processor.service';

@Component({
    selector: 'c8y-add-processor-extension',
    template: `<c8y-add-extension
      [headerText]="headerText"
      [headerIcon]="'upload'"
      [successText]="successText"
      [uploadExtensionHandler]="uploadExtensionHandler"
      [canGoBack]="true"
    ></c8y-add-extension>`
  })
  export class AddProcessorExtensionComponent {
    headerText: string = gettext('Upload processor extension');
    successText: string = gettext('Extension created');
  
    constructor(private processorService: ProcessorService) {}
  
    uploadExtensionHandler = (file: File, app: IManagedObject) => this.uploadExtension(file, app );
  
    async uploadExtension(file: File, app: IManagedObject): Promise<IManagedObjectBinary> {
      return this.processorService.uploadExtension(file, app);
    }

  }