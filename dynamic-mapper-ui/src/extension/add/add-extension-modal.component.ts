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
import {
  Component,
  OnDestroy,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import {
  ApplicationService,
  IApplication,
  IManagedObject,
} from '@c8y/client';
import {
  AlertService,
  DropAreaComponent,
  gettext,
  IFetchWithProgress,
  ModalLabels
} from '@c8y/ngx-components';
import { Observable, Subject } from 'rxjs';
import { ERROR_MESSAGES } from '../share/extension.constants';
import { ExtensionService } from '../extension.service';

@Component({
  selector: 'd11r-mapping-add-extension',
  templateUrl: './add-extension-modal.component.html',
  styleUrls: ['./add-extension-modal.component.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class AddExtensionComponent implements OnDestroy {
  @ViewChild(DropAreaComponent) dropAreaComponent;

  isLoading: boolean;
  isAppCreated: boolean;
  app: Partial<IManagedObject>;
  uploadProgress: IFetchWithProgress | null = null;
  progress$: Subject<number> = new Subject<number>();
  errorMessage: string;
  private uploadCanceled: boolean = false;
  closeSubject: Subject<boolean> = new Subject();
  labels: ModalLabels = { cancel: 'Cancel', ok: 'Done' };

  constructor(
    private extensionService: ExtensionService,
    private alertService: AlertService,
    private applicationService: ApplicationService
  ) { }

  ngOnDestroy(): void {
    this.closeSubject.complete();
  }


  onFileDroppedEvent(event) {
    if (event && event.length > 0) {
      // eslint-disable-next-line prefer-destructuring
      const file = event[0].file;
      this.onFile(file);
    }
  }

  async onFile(file: File) {
    this.isLoading = true;
    this.errorMessage = null;
    this.progress$.next(0); // Reset progress
    const nameUpload = file.name.split('.').slice(0, -1).join('.');

    try {
      this.app = {
        d11r_processorExtension: {
          name: nameUpload,
          external: true
        },
        name: nameUpload
      };

      const progress: Observable<IFetchWithProgress> = this.extensionService.uploadProcessorExtensionWithProgress$(file, this.app);
      this.isAppCreated = true;

      // Subscribe to the progress Observable
      progress.subscribe(
        {
          next: (uploadProgress) => {
            // Only emit the percentage to progress$ Subject
            this.progress$.next(Math.round(uploadProgress.percentage));
          },
          error: (error) => {
            // Handle errors
            this.isLoading = false;
            this.progress$.next(0); // Reset progress on error
            this.extensionService.cancelProcessorExtensionCreation(this.app);
            this.app = null;
            this.dropAreaComponent.onDelete();

            this.errorMessage = ERROR_MESSAGES[error.message];
            if (!this.errorMessage && !this.uploadCanceled) {
              this.alertService.addServerFailure(error);
            }
          },
          complete: () => {
            // Handle successful completion
            this.isLoading = false;
            this.progress$.next(100); // Set to 100% on completion
            this.alertService.success(gettext('Processor extension uploaded successfully.'));

            // Optional: Reset progress after a delay
            setTimeout(() => this.progress$.next(0), 2000);
          }
        }
      );

    } catch (ex) {
      // Handle synchronous errors
      this.isLoading = false;
      this.progress$.next(0); // Reset progress on error
      this.extensionService.cancelProcessorExtensionCreation(this.app);
      this.app = null;
      this.dropAreaComponent.onDelete();
      this.errorMessage = ERROR_MESSAGES[ex.message];
      if (!this.errorMessage && !this.uploadCanceled) {
        this.alertService.addServerFailure(ex);
      }
    }
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