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
import { ApplicationRef, Component, NgZone, OnDestroy, ViewEncapsulation } from '@angular/core';
import { AlertService, CoreModule, ModalLabels } from '@c8y/ngx-components';
import { BehaviorSubject, Subject } from 'rxjs';
import { ServiceConfiguration } from '../shared/configuration.model';
import { SharedService } from '../../shared';

/**
 * Restores a previously exported service configuration.
 *
 * <p>This is a snapshot restore, not a merge: the imported document replaces the current
 * settings. The flow is therefore two-step — drop the file, see what it contains, then
 * explicitly confirm the replacement — rather than applying the moment a file is dropped.
 */
@Component({
  selector: 'd11r-import-service-configuration',
  templateUrl: './import-service-configuration-modal.component.html',
  styleUrls: ['./import-service-configuration-modal.component.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule]
})
export class ImportServiceConfigurationComponent implements OnDestroy {
  progress$: BehaviorSubject<number> = new BehaviorSubject<number>(null);
  isLoading = false;
  isImported = false;
  errorMessage: string;
  successText = 'Service configuration restored';
  closeSubject: Subject<boolean> = new Subject();

  /** Parsed file, held until the user confirms the replacement. */
  pendingConfiguration: Partial<ServiceConfiguration>;
  pendingSettingsCount = 0;
  pendingCodeTemplateCount = 0;
  fileName: string;

  labels: ModalLabels = { cancel: 'Cancel', ok: 'Replace settings' };

  constructor(
    private sharedService: SharedService,
    private alertService: AlertService,
    private ngZone: NgZone,
    private appRef: ApplicationRef
  ) {}

  async onFileDroppedEvent(event): Promise<void> {
    try {
      if (event && event.length > 0) {
        await this.onFile(event[0].file);
      }
    } catch (error) {
      this.alertService.warning('Import failed. Verify the format of the import file!');
    }
  }

  /** Parses and validates the file. Nothing is applied here — see {@link applyImport}. */
  async onFile(file: File): Promise<void> {
    this.errorMessage = null;
    this.pendingConfiguration = undefined;
    this.isLoading = true;
    this.progress$.next(0);

    try {
      const parsed = JSON.parse(await file.text());
      if (Array.isArray(parsed) || parsed === null || typeof parsed !== 'object') {
        // A connectors or mappings export is an array — a likely mix-up, so name it.
        throw new Error(
          'The file must contain a single service configuration object, not a list. ' +
            'Connector and mapping exports cannot be imported here.'
        );
      }

      const { codeTemplates, ...settings } = parsed as Partial<ServiceConfiguration>;
      this.pendingSettingsCount = Object.keys(settings).length;
      this.pendingCodeTemplateCount = Object.keys(codeTemplates ?? {}).length;
      if (this.pendingSettingsCount === 0 && this.pendingCodeTemplateCount === 0) {
        throw new Error('The file contains no service configuration settings.');
      }

      this.fileName = file.name;
      this.pendingConfiguration = parsed as Partial<ServiceConfiguration>;
    } catch (error) {
      this.errorMessage = `Import failed: ${error?.message ?? 'invalid file'}`;
    } finally {
      this.ngZone.run(() => {
        this.isLoading = false;
        this.progress$.next(100);
        this.appRef.tick();
      });
    }
  }

  /** Discards the parsed file and returns to the drop area, so a wrong file can be swapped. */
  onChooseAnotherFile(): void {
    this.pendingConfiguration = undefined;
    this.errorMessage = null;
    this.progress$.next(null);
  }

  private async applyImport(): Promise<boolean> {
    try {
      const response = await this.sharedService.updateServiceConfiguration(this.pendingConfiguration);
      if (response.status < 200 || response.status >= 300) {
        this.errorMessage = `Import failed: the service rejected the configuration (HTTP ${response.status}).`;
        return false;
      }
      this.alertService.success('Service configuration restored from file.');
      return true;
    } catch (error) {
      this.errorMessage = `Import failed: ${error?.message ?? 'unknown error'}`;
      return false;
    }
  }

  /** Cancel/ESC — nothing was applied, so the page does not need to reload its form. */
  onDismiss(): void {
    this.closeSubject.next(false);
    this.closeSubject.complete();
  }

  /**
   * "Replace settings". Applies the pending document; on failure the modal stays open with the
   * error visible instead of reporting a restore that did not happen.
   */
  async onConfirm(): Promise<void> {
    if (!this.pendingConfiguration) {
      this.onDismiss();
      return;
    }
    this.isLoading = true;
    const applied = await this.applyImport();
    this.ngZone.run(() => {
      this.isLoading = false;
      this.isImported = applied;
      this.appRef.tick();
    });
    if (applied) {
      this.closeSubject.next(true);
      this.closeSubject.complete();
    }
  }

  ngOnDestroy(): void {
    this.progress$.complete();
    // Backdrop/ESC dismissal bypasses onDismiss()/onConfirm(); completing here (idempotent)
    // keeps the caller's subscription from outliving the modal.
    this.closeSubject.complete();
  }
}
