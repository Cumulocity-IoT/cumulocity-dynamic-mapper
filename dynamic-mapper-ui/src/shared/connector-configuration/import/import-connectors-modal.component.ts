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
import { ConnectorConfigurationService } from '../../service/connector-configuration.service';
import { createCustomUuid } from '../../mapping/util';
import { ConnectorConfiguration, ConnectorType } from '../connector.model';

const SENSITIVE_PLACEHOLDER = '****';

@Component({
  selector: 'd11r-mapping-import-connectors',
  templateUrl: './import-connectors-modal.component.html',
  styleUrls: ['./import-connectors-modal.component.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule]
})
export class ImportConnectorsComponent implements OnDestroy {
  @ViewChild(DropAreaComponent) dropAreaComponent;
  progress$: BehaviorSubject<number> = new BehaviorSubject<number>(null);
  isLoading: boolean = false;
  isAppCreated: boolean = false;
  errorMessage: string;
  successText: string = 'Imported connectors';
  closeSubject: Subject<boolean> = new Subject();
  labels: ModalLabels = { cancel: 'Cancel', ok: 'Done' };

  constructor(
    private connectorConfigurationService: ConnectorConfigurationService,
    private alertService: AlertService,
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
      this.alertService.warning(`Import failed. Verify the format of the import file!`);
    }
  }

  async onFile(file: File) {
    this.isLoading = true;
    this.errorMessage = null;
    this.isAppCreated = false;
    this.progress$.next(0);

    const ms = await file.text();
    const allConfigurations: ConnectorConfiguration[] = JSON.parse(ms);
    // The HTTP connector is created automatically and must not be duplicated via import.
    const configurations = allConfigurations.filter(config => config.connectorType !== ConnectorType.HTTP);
    const skippedCount = allConfigurations.length - configurations.length;
    const countConfigurations = configurations.length;
    const errors = [];
    let successCount = 0;
    let maskedSecretsFound = false;

    for (let i = 0; i < configurations.length; i++) {
      const config = configurations[i];
      try {
        if (this.containsMaskedSecrets(config)) {
          maskedSecretsFound = true;
        }
        config.identifier = createCustomUuid();
        // Imported connectors never carry working credentials (sensitive
        // properties are exported as "****"), so keep them disabled until
        // the user reviews and re-enters the real values.
        config.enabled = false;
        await this.connectorConfigurationService.createConfiguration(config);
        successCount++;
        this.progress$.next((100 * (i + 1)) / countConfigurations);
      } catch (ex) {
        const errorMsg = `Failed to import connector ${config.name}`;
        errors.push(errorMsg);
      }
    }

    if (errors.length === 0) {
      this.alertService.success(`Imported ${successCount} connector(s) successfully.`);
    } else if (successCount === 0) {
      this.errorMessage = `Failed to import connectors. ${errors.length} error(s) occurred.`;
    } else {
      this.alertService.warning(`Import completed: ${successCount} succeeded, ${errors.length} failed.`);
    }

    if (maskedSecretsFound) {
      this.alertService.warning(
        'Imported connectors were disabled because sensitive properties (e.g. passwords, tokens) are not exported. Please edit them and re-enter the real values before enabling.'
      );
    }

    if (skippedCount > 0) {
      this.alertService.warning(
        `Skipped ${skippedCount} HTTP connector(s): the HTTP connector is created automatically and cannot be imported.`
      );
    }

    this.ngZone.run(() => {
      this.isLoading = false;
      this.progress$.next(100);
      this.isAppCreated = true;
      this.appRef.tick();
    });
  }

  private containsMaskedSecrets(config: ConnectorConfiguration): boolean {
    return Object.values(config.properties || {}).some(
      (value) => value === SENSITIVE_PLACEHOLDER
    );
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
