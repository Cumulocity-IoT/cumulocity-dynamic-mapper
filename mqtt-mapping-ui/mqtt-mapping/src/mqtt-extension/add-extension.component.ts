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
import { Component, Input, ViewChild } from '@angular/core';
import { ApplicationService, IApplication, IManagedObject } from '@c8y/client';
import { AlertService, DropAreaComponent, WizardComponent } from '@c8y/ngx-components';
import { BehaviorSubject } from 'rxjs';
import { ERROR_MESSAGES } from './extension.constants';
import { ExtensionService } from './extension.service';

@Component({
  selector: 'mapping-add-extension',
  templateUrl: './add-extension.component.html'
})
export class AddExtensionComponent {
  @Input() headerText: string;
  @Input() headerIcon: string;
  @Input() successText: string;
  @Input() uploadExtensionHandler: any;
  @Input() canGoBack: boolean = false;

  @ViewChild(DropAreaComponent) dropAreaComponent;

  isLoading: boolean;
  isAppCreated: boolean;
  createdApp: Partial<IManagedObject>;
  canOpenInBrowser: boolean = false;
  errorMessage: string;
  private uploadCanceled: boolean = false;
  
  constructor(
    private extensionService: ExtensionService,
    private alertService: AlertService,
    private applicationService: ApplicationService,
    private wizardComponent: WizardComponent
  ) { }

  get progress(): BehaviorSubject<number> {
    return this.extensionService.progress;
  }

  onFileDroppedEvent(event) {
    if (event && event.length > 0) {
      const file = event[0].file;
      this.onFile(file);
    }
  }

  async onFile(file: File) {
    this.isLoading = true;
    this.errorMessage = null;
    this.progress.next(0);
    const nameUpload = file.name.split('.').slice(0, -1).join('.')
    // constant PROCESSOR_EXTENSION_TYPE
    try {
      this.createdApp = {
        c8y_mqttMapping_Extension:
        {
          name: nameUpload,
          external: true
        },
        name: nameUpload,
      }
      await this.uploadExtensionHandler(file, this.createdApp);
      this.isAppCreated = true;
    } catch (ex) {
      this.extensionService.cancelExtensionCreation(this.createdApp);
      this.createdApp = null;
      this.dropAreaComponent.onDelete();
      this.errorMessage = ERROR_MESSAGES[ex.message];
      if (!this.errorMessage && !this.uploadCanceled) {
        this.alertService.addServerFailure(ex);
      }
    }
    this.progress.next(100);
    this.isLoading = false;
  }

  getHref(app: IApplication): string {
    return this.applicationService.getHref(app);
  }

  cancel() {
    this.cancelFileUpload();
    // this.wizardComponent.close();
  }

  done() {
    // this.wizardComponent.close();
  }

  back() {
    // this.wizardComponent.reset();
  }

  private cancelFileUpload() {
    this.uploadCanceled = true;
    this.extensionService.cancelExtensionCreation(this.createdApp);
    this.createdApp = null;
  }
}
