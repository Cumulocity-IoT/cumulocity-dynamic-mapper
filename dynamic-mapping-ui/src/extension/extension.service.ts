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

import { Injectable, OnDestroy } from '@angular/core';
import {
  IManagedObject,
  InventoryBinaryService,
  InventoryService,
  FetchClient} from '@c8y/client';
import { BehaviorSubject, forkJoin, from, map, Observable, Subject } from 'rxjs';

import {
  BASE_URL,
  Extension,
  ExtensionStatus,
  PATH_EXTENSION_ENDPOINT,
  PROCESSOR_EXTENSION_TYPE
} from '../shared';
import { FilesService, IFetchWithProgress } from '@c8y/ngx-components';
import { Stream } from 'stream';

interface ExtensionFilter {
  pageSize: number;
  withTotalPages: boolean;
  fragmentType: string;
}

@Injectable({ providedIn: 'root' })
export class ExtensionService implements OnDestroy {
  // Private subjects
  readonly appDeleted$ = new Subject<IManagedObject>();

  // Configuration
  private readonly CONFIG = {
    PAGE_SIZE: 100,
    UPLOAD_PROGRESS_MAX: 95,
    HTTP_TIMEOUT: 30000
  } as const;

  constructor(
    private readonly client: FetchClient,
    private readonly inventoryService: InventoryService,
    private readonly fileService: FilesService,
    private readonly inventoryBinaryService: InventoryBinaryService
  ) { }


  ngOnDestroy(): void {
    this.appDeleted$.complete();
  }


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

  async deleteProcessorExtension(app: IManagedObject): Promise<void> {
    if (!app?.name) {
      throw new Error('Invalid extension: missing name');
    }

    try {
      await this.deleteExtensionFromBackend(app.name);
      this.appDeleted$.next(app);
    } catch (error) {
      console.error(`Failed to delete extension ${app.name}:`, error);
      throw error;
    }
  }

  async cancelProcessorExtensionCreation(app: Partial<IManagedObject>): Promise<void> {
    if (!app?.id) {
      console.warn('Cannot cancel: invalid app object');
      return;
    }

    try {
      await this.inventoryBinaryService.delete(app);
    } catch (error) {
      console.error('Failed to cancel extension creation:', error);
      throw error;
    }
  }

  // Private helper methods

  private async deleteExtensionFromBackend(name: string): Promise<void> {
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
          method: 'DELETE'
        }
      );

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
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
      if (!ext?.name) {
        console.warn('Extension missing name:', ext);
        return {
          ...ext,
          loaded: ExtensionStatus.UNKNOWN
        };
      }

      const backendExt = backend.get(ext.name);

      if (backendExt?.loaded) {
        return {
          ...ext,
          loaded: backendExt.loaded,
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

  uploadProcessorExtensionWithProgress$(file: Stream | Buffer | File | Blob, app: Partial<IManagedObject>): Observable<IFetchWithProgress> {
    const uploadStartTimestamp = new Date().getTime();
    const subject = new BehaviorSubject<IFetchWithProgress>({
      percentage: 0,
      totalBytes: null,
      bufferedBytes: 0,
      bytesPerSecond: 0
    });
    const onProgress = (event: ProgressEvent) => {
      const eventTimestamp = new Date().getTime();
      const duration = eventTimestamp - uploadStartTimestamp;
      subject.next({
        percentage: Math.round((event.loaded / event.total) * 100),
        totalBytes: event.total,
        bufferedBytes: event.loaded,
        bytesPerSecond: Math.round(event.loaded / Math.round(duration / 1000))
      });
    };

    const xhr = this.inventoryBinaryService.createWithProgress(file, onProgress, app);
    const uploadPromise = this.inventoryBinaryService.getXMLHttpResponse(xhr);
    uploadPromise.then(() => {
      subject.complete();
    });

    return subject.asObservable();
  }

}