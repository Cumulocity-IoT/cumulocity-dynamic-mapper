/*
 * Copyright (c) 2025 Cumulocity GmbH
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
import { ApplicationService, IApplication, IManagedObject } from '@c8y/client';
import { AlertService, CoreModule, DropAreaComponent, ModalLabels } from '@c8y/ngx-components';
import { gettext } from '@c8y/ngx-components/gettext';
import { Observable, Subject, Subscription } from 'rxjs';
import { ERROR_MESSAGES } from '../share/extension.constants';
import { ExtensionService } from '../extension.service';

@Component({
  selector: 'd11r-mapping-add-extension',
  templateUrl: './add-extension-modal.component.html',
  styleUrls: ['./add-extension-modal.component.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule]
})
export class AddExtensionComponent implements OnDestroy {
  @ViewChild(DropAreaComponent) dropAreaComponent;

  isLoading = false;
  isAppCreated = false;
  app: Partial<IManagedObject>;
  progress$: Subject<number> = new Subject<number>();
  errorMessage: string;
  successText = gettext('Processor extension uploaded successfully.');

  private uploadCanceled = false;
  private uploadSubscription: Subscription;
  closeSubject: Subject<boolean> = new Subject();
  labels: ModalLabels = { cancel: 'Cancel', ok: 'Done' };

  constructor(
    private extensionService: ExtensionService,
    private alertService: AlertService,
    private applicationService: ApplicationService
  ) {}

  ngOnDestroy(): void {
    this.uploadSubscription?.unsubscribe();
    this.progress$.complete();
    this.closeSubject.complete();
  }


  onFileDroppedEvent(event: any[]): void {
    if (event?.[0]?.file) {
      this.onFile(event[0].file);
    }
  }

  async onFile(file: File): Promise<void> {
    this.resetUploadState();
    const nameUpload = this.extractFileName(file.name);

    try {
      this.app = this.createAppObject(nameUpload);
      const progress$ = this.extensionService.uploadProcessorExtensionWithProgress$(file, this.app);
      this.isAppCreated = true;

      this.uploadSubscription = progress$.subscribe({
        next: (uploadProgress) => {
          this.progress$.next(Math.round(uploadProgress.percentage));
        },
        error: (error) => this.handleUploadError(error),
        complete: () => this.handleUploadComplete()
      });
    } catch (ex) {
      this.handleUploadError(ex);
    }
  }

  private resetUploadState(): void {
    this.isLoading = true;
    this.errorMessage = null;
    this.progress$.next(0);
  }

  private extractFileName(fullName: string): string {
    return fullName.split('.').slice(0, -1).join('.');
  }

  private createAppObject(name: string): Partial<IManagedObject> {
    return {
      d11r_processorExtension: {
        name,
        external: true
      },
      name
    };
  }

  private handleUploadError(error: any): void {
    this.isLoading = false;
    this.progress$.next(0);
    this.extensionService.cancelProcessorExtensionCreation(this.app);
    this.app = null;
    this.dropAreaComponent?.onDelete();

    this.errorMessage = ERROR_MESSAGES[error.message];
    if (!this.errorMessage && !this.uploadCanceled) {
      this.alertService.addServerFailure(error);
    }
  }

  private handleUploadComplete(): void {
    this.isLoading = false;
    this.progress$.next(100);
    this.alertService.success(this.successText);

    setTimeout(() => this.progress$.next(0), 2000);
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
    this.extensionService.cancelProcessorExtensionCreation(this.app);
    this.app = null;
  }

}