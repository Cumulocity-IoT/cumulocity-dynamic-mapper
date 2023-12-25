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
import { Component, OnDestroy, ViewChild, ViewEncapsulation } from '@angular/core';
import {
  ApplicationService,
  IApplication,
  IManagedObject,
  IManagedObjectBinary
} from '@c8y/client';
import {
  AlertService,
  DropAreaComponent,
  ModalLabels
} from '@c8y/ngx-components';
import { BehaviorSubject, Subject } from 'rxjs';
import { ERROR_MESSAGES } from '../share/extension.constants';
import { ExtensionService } from '../share/extension.service';

@Component({
  selector: 'd11r-mapping-add-extension',
  templateUrl: './add-extension.component.html',
  styleUrls: ['./add-extension.component.style.css'],
  encapsulation: ViewEncapsulation.None
})
export class AddExtensionComponent implements OnDestroy {
  @ViewChild(DropAreaComponent) dropAreaComponent;

  isLoading: boolean;
  isAppCreated: boolean;
  createdApp: Partial<IManagedObject>;
  errorMessage: string;
  private uploadCanceled: boolean = false;
  closeSubject: Subject<boolean> = new Subject();
  labels: ModalLabels = { cancel: 'Cancel', ok: 'Done' };

  constructor(
    private extensionService: ExtensionService,
    private alertService: AlertService,
    private applicationService: ApplicationService
  ) {}


  ngOnDestroy(): void {
    this.closeSubject.complete();
  }

  get progress(): BehaviorSubject<number> {
    return this.extensionService.progress;
  }

  onFileDroppedEvent(event) {
    if (event && event.length > 0) {
      const [file] = event;
      this.onFile(file);
    }
  }

  async onFile(file: File) {
    this.isLoading = true;
    this.errorMessage = null;
    this.progress.next(0);
    const nameUpload = file.name.split('.').slice(0, -1).join('.');
    // constant PROCESSOR_EXTENSION_TYPE
    try {
      this.createdApp = {
        d11r_processorExtension: {
          name: nameUpload,
          external: true
        },
        name: nameUpload
      };
      this.createdApp = await this.uploadExtension(file, this.createdApp);
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

  onDismiss() {
    this.cancelFileUpload();
    this.closeSubject.next(true);
    this.closeSubject.complete();
  }

  onDone() {
    this.closeSubject.next(true);
    this.closeSubject.complete();
  }

  private cancelFileUpload() {
    this.uploadCanceled = true;
    this.extensionService.cancelExtensionCreation(this.createdApp);
    this.createdApp = null;
  }

  async uploadExtension(
    file: File,
    app: Partial<IManagedObject>
  ): Promise<IManagedObjectBinary> {
    return this.extensionService.uploadExtension(file, app);
  }
}
