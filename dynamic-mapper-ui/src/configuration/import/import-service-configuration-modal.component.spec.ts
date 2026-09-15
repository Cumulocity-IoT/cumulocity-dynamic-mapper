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
import { AlertService } from '@c8y/ngx-components';
import { ImportServiceConfigurationComponent } from './import-service-configuration-modal.component';
import { SharedService } from '../../shared';

/**
 * Restoring an exported service configuration.
 *
 * <p>The import replaces the tenant's settings, so the two properties that matter are: nothing
 * is applied before the user confirms, and a failed apply is never reported as a restore.
 * Exercised without TestBed — the modal template pulls in the c8y app-shell DI graph.
 */
describe('ImportServiceConfigurationComponent', () => {
  let sharedService: jasmine.SpyObj<SharedService>;
  let alertService: jasmine.SpyObj<AlertService>;
  let component: ImportServiceConfigurationComponent;

  function file(content: string, name = 'service-configuration.json'): File {
    return { name, text: () => Promise.resolve(content) } as unknown as File;
  }

  beforeEach(() => {
    sharedService = jasmine.createSpyObj<SharedService>('SharedService', [
      'updateServiceConfiguration'
    ]);
    alertService = jasmine.createSpyObj<AlertService>('AlertService', ['success', 'warning', 'danger']);
    // NgZone/ApplicationRef are only used to re-enter change detection.
    const ngZone = { run: (fn: () => void) => fn() } as any;
    const appRef = { tick: () => undefined } as any;
    component = new ImportServiceConfigurationComponent(sharedService, alertService, ngZone, appRef);
  });

  describe('reading the file', () => {
    it('accepts a configuration document and reports what it holds', async () => {
      await component.onFile(
        file(JSON.stringify({ logPayload: true, maxCPUTimeMS: 5000, codeTemplates: { a: {}, b: {} } }))
      );

      expect(component.errorMessage).toBeNull();
      expect(component.pendingConfiguration).toBeDefined();
      expect(component.pendingSettingsCount).toBe(2);
      expect(component.pendingCodeTemplateCount).toBe(2);
      expect(component.fileName).toBe('service-configuration.json');
    });

    it('does not apply anything before the user confirms', async () => {
      await component.onFile(file(JSON.stringify({ logPayload: true })));

      expect(sharedService.updateServiceConfiguration).not.toHaveBeenCalled();
      expect(component.isImported).toBeFalse();
    });

    it('rejects a connector or mapping export, which is an array', async () => {
      await component.onFile(file(JSON.stringify([{ identifier: 'abc' }])));

      expect(component.pendingConfiguration).toBeUndefined();
      expect(component.errorMessage).toContain('not a list');
    });

    it('rejects a file with no settings in it', async () => {
      await component.onFile(file(JSON.stringify({})));

      expect(component.pendingConfiguration).toBeUndefined();
      expect(component.errorMessage).toContain('no service configuration settings');
    });

    it('rejects malformed JSON without throwing', async () => {
      await component.onFile(file('{ not json'));

      expect(component.pendingConfiguration).toBeUndefined();
      expect(component.errorMessage).toContain('Import failed');
    });

    it('lets a wrong file be swapped for another one', async () => {
      await component.onFile(file(JSON.stringify({ logPayload: true })));

      component.onChooseAnotherFile();

      expect(component.pendingConfiguration).toBeUndefined();
      expect(component.errorMessage).toBeNull();
    });
  });

  describe('applying the restore', () => {
    it('sends the whole document, code templates included', async () => {
      const document = { logPayload: false, codeTemplates: { shared: { code: 'x' } } };
      sharedService.updateServiceConfiguration.and.resolveTo({ status: 201 } as any);
      await component.onFile(file(JSON.stringify(document)));

      await component.onConfirm();

      expect(sharedService.updateServiceConfiguration).toHaveBeenCalledWith(
        jasmine.objectContaining(document)
      );
      expect(component.isImported).toBeTrue();
    });

    it('signals the caller so the form reloads with the restored values', async () => {
      sharedService.updateServiceConfiguration.and.resolveTo({ status: 200 } as any);
      await component.onFile(file(JSON.stringify({ logPayload: true })));
      const closed = new Promise<boolean>(resolve => component.closeSubject.subscribe(resolve));

      await component.onConfirm();

      expect(await closed).toBeTrue();
    });

    it('does not claim success when the service rejects the document', async () => {
      sharedService.updateServiceConfiguration.and.resolveTo({ status: 400 } as any);
      await component.onFile(file(JSON.stringify({ logPayload: true })));

      await component.onConfirm();

      // The modal must stay open with the error visible rather than showing a restore that
      // did not happen.
      expect(component.isImported).toBeFalse();
      expect(component.errorMessage).toContain('HTTP 400');
      expect(alertService.success).not.toHaveBeenCalled();
    });

    it('does not claim success when the request throws', async () => {
      sharedService.updateServiceConfiguration.and.rejectWith(new Error('network down'));
      await component.onFile(file(JSON.stringify({ logPayload: true })));

      await component.onConfirm();

      expect(component.isImported).toBeFalse();
      expect(component.errorMessage).toContain('network down');
    });
  });
});
