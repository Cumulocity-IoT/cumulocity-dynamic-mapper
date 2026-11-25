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

import { Injectable } from '@angular/core';
import {
  IManagedObject,
  InventoryService,
  FetchClient
} from '@c8y/client';
import { forkJoin, from, map, Observable } from 'rxjs';

import {
  BASE_URL,
  Extension,
  ExtensionStatus,
  PATH_EXTENSION_ENDPOINT,
  PROCESSOR_EXTENSION_TYPE
} from '../shared';
import { IFetchWithProgress } from '@c8y/ngx-components';

interface ExtensionFilter {
  pageSize: number;
  withTotalPages: boolean;
  fragmentType: string;
}

/** The backend refuses to delete an extension that mappings still use; `message` names them. */
export class ExtensionInUseError extends Error {
  constructor(message: string, readonly mappings: { name: string; identifier: string; direction: string; active?: boolean }[]) {
    super(message);
    this.name = 'ExtensionInUseError';
  }
}

@Injectable({ providedIn: 'root' })
export class ExtensionService {
  // Configuration
  private readonly CONFIG = {
    PAGE_SIZE: 100,
    UPLOAD_PROGRESS_MAX: 95,
    HTTP_TIMEOUT: 30000
  } as const;

  constructor(
    private readonly client: FetchClient,
    private readonly inventoryService: InventoryService
  ) { }

  async getProcessorExtensions(): Promise<Map<string, Extension>> {
    try {
      const response = await this.client.fetch(
        `${BASE_URL}/${PATH_EXTENSION_ENDPOINT}`,
        {
          headers: {
            accept: 'application/json',
            'content-type': 'application/json'
          },
          method: 'GET'
        }
      );

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      const data = await response.json();
      return new Map(Object.entries(data || {}));
    } catch (error) {
      console.error('Failed to get processor extensions:', error);
      throw new Error(`Failed to load processor extensions: ${error.message}`);
    }
  }

  async getProcessorExtension(name: string): Promise<Extension> {
    if (!name?.trim()) {
      throw new Error('Extension name is required');
    }

    try {
      const response = await this.client.fetch(
        `${BASE_URL}/${PATH_EXTENSION_ENDPOINT}/${encodeURIComponent(name)}`,
        {
          headers: {
            accept: 'application/json',
            'content-type': 'application/json'
          },
          method: 'GET'
        }
      );

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      return await response.json();
    } catch (error) {
      console.error(`Failed to get processor extension ${name}:`, error);
      throw error;
    }
  }

  getEnrichedProcessorExtensions(extensionId?: string): Observable<IManagedObject[]> {
    const listOfExtensionsInventory$ = from(this.getProcessorExtensionAsMO(extensionId));
    const listOfExtensionsBackend$ = from(this.getProcessorExtensions());

    return forkJoin({
      inventory: listOfExtensionsInventory$,
      backend: listOfExtensionsBackend$
    }).pipe(
      map(({ inventory, backend }) => this.enrichProcessorExtensions(inventory, backend))
    );
  }

  async deleteProcessorExtension(app: IManagedObject, force = false): Promise<void> {
    if (!app?.['name']) {
      throw new Error('Invalid extension: missing name');
    }

    try {
      await this.deleteExtensionFromBackend(app['name'], force);
    } catch (error) {
      console.error(`Failed to delete extension ${app['name']}:`, error);
      throw error;
    }
  }

  // Private helper methods

  private async deleteExtensionFromBackend(name: string, force: boolean): Promise<void> {
    if (!name?.trim()) {
      throw new Error('Extension name is required');
    }

    try {
      const response = await this.client.fetch(
        `${BASE_URL}/${PATH_EXTENSION_ENDPOINT}/${encodeURIComponent(name)}${force ? '?force=true' : ''}`,
        {
          headers: {
            accept: 'application/json',
            'content-type': 'application/json'
          },
          method: 'DELETE'
        }
      );

      if (!response.ok) {
        // 409 carries the mappings that still use the extension, 400 the reason it cannot be deleted
        const body = await response.json().catch(() => undefined);
        const message = body?.message || body?.error || `HTTP ${response.status}: ${response.statusText}`;
        if (response.status === 409) {
          throw new ExtensionInUseError(message, body?.mappings ?? []);
        }
        throw new Error(message);
      }
    } catch (error) {
      console.error(`Failed to delete processor extension ${name}:`, error);
      throw error;
    }
  }

  private enrichProcessorExtensions(
    inventory: IManagedObject[],
    backend: Map<string, Extension>
  ): IManagedObject[] {
    return inventory.map((ext) => {
      if (!ext?.['name']) {
        console.warn('Extension missing name:', ext);
        return {
          ...ext,
          loaded: ExtensionStatus.UNKNOWN
        };
      }

      const backendExt = backend.get(ext['name']);

      if (backendExt?.loaded) {
        return {
          ...ext,
          loaded: backendExt.loaded,
          message: backendExt.message,
          external: backendExt.external,
          extensionEntries: Object.values(backendExt.extensionEntries || {})
        };
      }

      return {
        ...ext,
        loaded: ExtensionStatus.UNKNOWN
      };
    });
  }


  private async getProcessorExtensionAsMO(extensionId?: string): Promise<IManagedObject[]> {
    try {
      if (extensionId?.trim()) {
        const result = await this.inventoryService.detail(extensionId);
        return [result.data];
      }

      const filter: ExtensionFilter = {
        pageSize: this.CONFIG.PAGE_SIZE,
        withTotalPages: true,
        fragmentType: PROCESSOR_EXTENSION_TYPE
      };

      const result = await this.inventoryService.list(filter);
      return result.data || [];
    } catch (error) {
      console.error('Failed to get extensions:', error);
      throw new Error(`Failed to load extensions: ${error.message}`);
    }
  }

  /**
   * Uploads the JAR through the backend (`POST /extension`), which stores it, registers it under `name`, loads it and
   * rolls a new upload back if nothing can be loaded. XMLHttpRequest rather than fetch: only it reports upload progress.
   *
   * The observable completes when the extension is loaded and errors with `{ data: <backend error body> }` (a rejected
   * upload) or a plain string (no response). Unsubscribing aborts the upload.
   */
  uploadProcessorExtensionWithProgress$(
    file: File,
    options: { name?: string; overwrite?: boolean } = {}
  ): Observable<IFetchWithProgress> {
    return new Observable<IFetchWithProgress>(subscriber => {
      const uploadStartTimestamp = Date.now();
      const body = new FormData();
      body.append('file', file, file.name);
      if (options.name) {
        body.append('name', options.name);
      }
      body.append('overwrite', String(!!options.overwrite));

      // The same auth headers the c8y client sends; the content type is left to the browser (multipart boundary)
      const headers: Record<string, string> = { ...(this.client.getFetchOptions().headers as Record<string, string>), accept: 'application/json' };
      Object.keys(headers).filter(key => key.toLowerCase() === 'content-type').forEach(key => delete headers[key]);

      const xhr = new XMLHttpRequest();
      xhr.open('POST', `/${BASE_URL}/${PATH_EXTENSION_ENDPOINT}`, true);
      Object.entries(headers).forEach(([key, value]) => xhr.setRequestHeader(key, value));

      xhr.upload.addEventListener('progress', (event: ProgressEvent) => {
        const duration = Date.now() - uploadStartTimestamp;
        subscriber.next({
          percentage: event.total ? Math.round((event.loaded / event.total) * 100) : 0,
          totalBytes: event.total,
          bufferedBytes: event.loaded,
          bytesPerSecond: Math.round(event.loaded / Math.max(1, Math.round(duration / 1000)))
        });
      });
      xhr.addEventListener('loadend', () => {
        if (xhr.readyState !== 4) {
          return;
        }
        if (xhr.status === 200 || xhr.status === 201) {
          subscriber.complete();
          return;
        }
        let data: unknown;
        try {
          data = JSON.parse(xhr.responseText);
        } catch {
          data = undefined;
        }
        subscriber.error(data ? { data } : 'Could not upload file.');
      });
      xhr.send(body);

      return () => xhr.abort();
    });
  }

}