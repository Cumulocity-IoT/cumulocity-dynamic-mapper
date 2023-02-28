/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */
import { Component } from '@angular/core';
import { IManagedObject, IManagedObjectBinary } from '@c8y/client';
import { gettext } from '@c8y/ngx-components';
import { ExtensionService } from './share/extension.service';

@Component({
    selector: 'mapping-add-extension-wizard',
    template: `<mapping-add-extension
      [headerText]="headerText"
      [headerIcon]="'upload'"
      [successText]="successText"
      [uploadExtensionHandler]="uploadExtensionHandler"
      [canGoBack]="true"
    ></mapping-add-extension>`
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