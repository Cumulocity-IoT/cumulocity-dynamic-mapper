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

import { EventEmitter, Injectable } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import {
  IManagedObject,
  IManagedObjectBinary,
  InventoryBinaryService,
  InventoryService
} from '@c8y/client';
import { FetchClient, IFetchResponse } from '@c8y/client';
import {
  BASE_URL,
  Extension,
  ExtensionStatus,
  PATH_EXTENSION_ENDPOINT,
  PROCESSOR_EXTENSION_TYPE
} from '../shared';
import * as _ from 'lodash';
import { AlertService, gettext, ModalService } from '@c8y/ngx-components';
import { BehaviorSubject, forkJoin, from, map, Observable } from 'rxjs';
import { HttpStatusCode } from '@angular/common/http';

@Injectable({ providedIn: 'root' })
export class ExtensionService {
  appDeleted = new EventEmitter<IManagedObject>();
  progress: BehaviorSubject<number> = new BehaviorSubject<number>(null);
  constructor(
    private client: FetchClient,
    private modal: ModalService,
    private alertService: AlertService,
    private translateService: TranslateService,
    private inventoryService: InventoryService,
    private inventoryBinaryService: InventoryBinaryService
  ) {}

  async getExtensions(extensionId: string): Promise<IManagedObject[]> {
    const filter: object = {
      pageSize: 100,
      withTotalPages: true,
      fragmentType: PROCESSOR_EXTENSION_TYPE
    };

    let result = [];
    if (!extensionId) {
      result = (await this.inventoryService.list(filter)).data;
    } else {
      result.push((await this.inventoryService.detail(extensionId)).data);
    }
    return result;
  }

  async getProcessorExtensions(): Promise<Map<string,Extension>> {
    const response: IFetchResponse = await this.client.fetch(
      `${BASE_URL}/${PATH_EXTENSION_ENDPOINT}`,
      {
        headers: {
          accept: 'application/json',
          'content-type': 'application/json'
        },
        method: 'GET'
      }
    );

    if (response.status != HttpStatusCode.Ok) {
      return undefined;
    }
    return response.json();
  }

  async getProcessorExtension(name: string): Promise<Extension> {
    const response: IFetchResponse = await this.client.fetch(
      `${BASE_URL}/${PATH_EXTENSION_ENDPOINT}/${name}`,
      {
        headers: {
          accept: 'application/json',
          'content-type': 'application/json'
        },
        method: 'GET'
      }
    );

    if (response.status != HttpStatusCode.Ok) {
      return undefined;
    }
    // let result =  (await response.json()) as string[];
    return response.json();
  }

  async deleteProcessorExtension(name: string): Promise<string> {
    const response: IFetchResponse = await this.client.fetch(
      `${BASE_URL}/${PATH_EXTENSION_ENDPOINT}/${name}`,
      {
        headers: {
          accept: 'application/json',
          'content-type': 'application/json'
        },
        method: 'DELETE'
      }
    );

    if (response.status != HttpStatusCode.Ok) {
      return undefined;
    }
    // let result =  (await response.json()) as string[];
    return response.json();
  }

  getExtensionsEnriched(extensionId: string): Observable<IManagedObject[]> {
    const listOfExtensionsInventory$ = from(this.getExtensions(extensionId));
    const listOfExtensionsBackend$ = from(this.getProcessorExtensions());

    return forkJoin({
      inventory: listOfExtensionsInventory$,
      backend: listOfExtensionsBackend$
    }).pipe(
      map(({ inventory, backend }) => {
        return inventory.map((ext) => {
          const backendExt = backend[ext['name']];
          if (backendExt?.loaded) {
            return {
              ...ext,
              loaded: backendExt.loaded,
              external: backendExt.external,
              extensionEntries: _.values(backendExt.extensionEntries)
            };
          } else {
            return {
              ...ext,
              loaded: ExtensionStatus.UNKNOWN
            };
          }
        });
      })
    );
  }

  async deleteExtension(app: IManagedObject): Promise<void> {
    await this.deleteProcessorExtension(app['name']);
    this.alertService.success(gettext('Extension deleted.'));
    this.appDeleted.emit(app);
  }

  updateUploadProgress(event): void {
    if (event.lengthComputable) {
      const currentProgress = this.progress.value;
      this.progress.next(
        currentProgress + (event.loaded / event.total) * (95 - currentProgress)
      );
    }
  }

  async uploadExtension(
    archive: File,
    app: Partial<IManagedObject>
  ): Promise<IManagedObjectBinary> {
    const result = (await this.inventoryBinaryService.create(archive, app))
      .data;

    return result;
  }

  cancelExtensionCreation(app: Partial<IManagedObject>): void {
    if (app) {
      this.inventoryBinaryService.delete(app);
    }
  }
}
