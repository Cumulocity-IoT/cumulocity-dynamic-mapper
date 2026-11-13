/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
import { gettext } from '@c8y/ngx-components/gettext';
import { TranslateService } from '@ngx-translate/core';
import { ApplicationRef, Component, NgZone, OnDestroy, ViewChild, ViewEncapsulation, inject, ChangeDetectionStrategy, signal, viewChild } from '@angular/core';
import {
  CoreModule,
  DropAreaComponent,
  ModalLabels
} from '@c8y/ngx-components';
import { BehaviorSubject, Subject } from 'rxjs';
import { MappingService } from '../core/mapping.service';
import { createCustomUuid, Mapping } from '../../shared';

@Component({
  selector: 'd11r-mapping-import-extension',
  templateUrl: './import-mappings.component.html',
  styleUrls: ['./import-mappings.component.style.css'],
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports:[CoreModule]
})
export class ImportMappingsComponent implements OnDestroy {
  private readonly translateService = inject(TranslateService);
  readonly dropAreaComponent = viewChild(DropAreaComponent);
  progress$: BehaviorSubject<number> = new BehaviorSubject<number>(null);
  private readonly isLoadingState = signal<boolean>(false);
  get isLoading(): boolean { return this.isLoadingState(); }
  set isLoading(value: boolean) { this.isLoadingState.set(value); }
  private readonly isAppCreatedState = signal<boolean>(false);
  get isAppCreated(): boolean { return this.isAppCreatedState(); }
  set isAppCreated(value: boolean) { this.isAppCreatedState.set(value); }
  private readonly errorMessageState = signal<string | null>(null);
  get errorMessage(): string | null { return this.errorMessageState(); }
  set errorMessage(value: string | null) { this.errorMessageState.set(value); }
  private readonly resultTypeState = signal<'success' | 'warning' | 'error'>('success');
  get resultType(): 'success' | 'warning' | 'error' { return this.resultTypeState(); }
  set resultType(value: 'success' | 'warning' | 'error') { this.resultTypeState.set(value); }
  private readonly resultTextState = signal<string>('');
  get resultText(): string { return this.resultTextState(); }
  set resultText(value: string) { this.resultTextState.set(value); }
  private readonly failedMappingsState = signal<string[]>([]);
  get failedMappings(): string[] { return this.failedMappingsState(); }
  set failedMappings(value: string[]) { this.failedMappingsState.set(value); }
  closeSubject: Subject<boolean> = new Subject();
  labels: ModalLabels = { cancel: 'Cancel', ok: 'Done' };

  constructor(
    private mappingService: MappingService,
    private ngZone: NgZone,
    private appRef: ApplicationRef
  ) { }

  async onFileDroppedEvent(event) {
    try {
      if (event && event.length > 0) {
        const file = event[0].file;
        await this.onFile(file);
      }
    } catch (error) {
      this.errorMessage = this.translateService.instant(gettext('Import failed. Verify the format of the import file!'));
      this.isLoading = false;
      this.progress$.next(null);
    }
  }

  async onFile(file: File) {
    this.isLoading = true;
    this.errorMessage = null;
    this.isAppCreated = false;
    this.failedMappings = [];
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
        errors.push(m.name);
      }
    }

    this.failedMappings = errors;
    if (errors.length === 0) {
      this.resultType = 'success';
      this.resultText = this.translateService.instant(gettext('Imported {{successCount}} mapping(s) successfully.'), { successCount });
    } else if (successCount === 0) {
      this.resultType = 'error';
      this.resultText = this.translateService.instant(gettext('Failed to import mappings. {{errorCount}} error(s) occurred.'), { errorCount: errors.length });
    } else {
      this.resultType = 'warning';
      this.resultText = this.translateService.instant(gettext('Import completed: {{successCount}} succeeded, {{errorCount}} failed.'), { successCount, errorCount: errors.length });
    }

    // Use NgZone.run to ensure the view updates run inside Angular's zone
    this.ngZone.run(() => {
      this.isLoading = false;
      this.progress$.next(100);
      this.isAppCreated = true;

      // Force application-wide change detection for dynamically created modal
      this.appRef.tick();
    });
  }

  onDismiss() {
    this.closeSubject.next(true);
    this.closeSubject.complete();
  }

  onDone() {
    this.closeSubject.next(true);
    this.closeSubject.complete();
  }

  ngOnDestroy() {
    this.progress$.complete();
  }
}
