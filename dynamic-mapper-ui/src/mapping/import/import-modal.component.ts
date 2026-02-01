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
  ApplicationRef,
  ChangeDetectorRef,
  Component,
  NgZone,
  OnDestroy,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import {
  AlertService,
  CoreModule,
  DropAreaComponent,
  ModalLabels
} from '@c8y/ngx-components';
import { BehaviorSubject, Subject } from 'rxjs';
import { MappingService } from '../core/mapping.service';
import { createCustomUuid, Mapping } from '../../shared';

@Component({
  selector: 'd11r-mapping-import-extension',
  templateUrl: './import-modal.component.html',
  styleUrls: ['./import-modal.component.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports:[CoreModule]
})
export class ImportMappingsComponent implements OnDestroy {
  @ViewChild(DropAreaComponent) dropAreaComponent;
  private importCanceled: boolean = false;
  progress$: BehaviorSubject<number> = new BehaviorSubject<number>(null);
  isLoading: boolean = false;
  isAppCreated: boolean = false;
  errorMessage: string;
  successText: string = 'Imported mappings';
  closeSubject: Subject<boolean> = new Subject();
  labels: ModalLabels = { cancel: 'Cancel', ok: 'Done' };

  constructor(
    private mappingService: MappingService,
    private alertService: AlertService,
    private cdr: ChangeDetectorRef,
    private ngZone: NgZone,
    private appRef: ApplicationRef
  ) { }

  async onFileDroppedEvent(event) {
    try {
      if (event && event.length > 0) {
        // eslint-disable-next-line prefer-destructuring
        const file = event[0].file;
        await this.onFile(file);
      }
    } catch (error) {
      this.alertService.warning(`Import failed. Verify the format of the import file!`);
    }
  }

  async onFile(file: File) {
    this.isLoading = true;
    this.errorMessage = null;
    this.isAppCreated = false;
    this.progress$.next(0);

    const ms = await file.text();
    const mappings: Mapping[] = JSON.parse(ms);
    const countMappings = mappings.length;
    const errors = [];
    let successCount = 0;

    for (let i = 0; i < mappings.length; i++) {
      const m = mappings[i];
      try {
        m.identifier = createCustomUuid();
        m.lastUpdate = Date.now();
        m.active = false;
        await this.mappingService.createMapping(m);
        successCount++;
        this.progress$.next((100 * (i + 1)) / countMappings);
      } catch (ex) {
        const errorMsg = `Failed to import mapping ${m.name}`;
        errors.push(errorMsg);
        this.alertService.warning(`${errorMsg}: ${ex}`);
      }
    }

    // Only set error message if ALL imports failed
    if (errors.length > 0 && successCount === 0) {
      this.errorMessage = `Failed to import mappings. ${errors.length} error(s) occurred.`;
    } else if (errors.length > 0) {
      // Some succeeded, some failed
      this.alertService.warning(`Import completed with errors. ${successCount} succeeded, ${errors.length} failed.`);
    }

    console.log('Import completed:', { successCount, errors: errors.length, total: countMappings });

    // Use NgZone.run to ensure the view updates run inside Angular's zone
    this.ngZone.run(() => {
      this.isLoading = false;
      this.progress$.next(100);
      this.isAppCreated = true;
      console.log('isAppCreated set to:', this.isAppCreated);
      console.log('Triggering change detection...');

      // Force application-wide change detection for dynamically created modal
      this.appRef.tick();
    });
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
    this.importCanceled = true;
  }

  ngOnDestroy() {
    this.progress$.unsubscribe();
  }
}
