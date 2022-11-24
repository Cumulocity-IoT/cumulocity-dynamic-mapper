/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
import {
    IManagedObject, IManagedObjectBinary, InventoryBinaryService, InventoryService
} from '@c8y/client';

import {
    AlertService, gettext, ModalService, Status
} from '@c8y/ngx-components';
import * as _ from 'lodash';

import { TranslateService } from '@ngx-translate/core';
import { BehaviorSubject } from 'rxjs';
import { ExtensionStatus } from '../shared/mapping.model';
import { PROCESSOR_EXTENSION_TYPE } from '../shared/util';
import { BrokerConfigurationService } from '../mqtt-configuration/broker-configuration.service';

@Injectable({ providedIn: 'root' })
export class ExtensionService {

    appDeleted = new EventEmitter<IManagedObject>();
    progress: BehaviorSubject<number> = new BehaviorSubject<number>(null);
    protected baseUrl: string;

    constructor(
        private modal: ModalService,
        private alertService: AlertService,
        private translateService: TranslateService,
        private inventoryService: InventoryService,
        private inventoryBinaryService: InventoryBinaryService,
        private configurationService: BrokerConfigurationService,
    ) { }

    async getExtensions(customFilter: any = {}): Promise<IManagedObject[]> {
        const filter: object = {
            pageSize: 100,
            withTotalPages: true,
            fragmentType: PROCESSOR_EXTENSION_TYPE,
        };
        const query: object = {
            //   fragmentType: 'pas_extension',
        };
        Object.assign(query, customFilter);
        let result;
        if (Object.keys(customFilter).length == 0) {
            result = (await this.inventoryService.list(filter)).data;
        } else {
            result = (await this.inventoryService.listQuery(query, filter)).data;
        }

        return result;
    }
    async getExtensionsEnriched(customFilter: any = {}): Promise<IManagedObject[]> {
        let listOfExtensionsInventory: Promise<IManagedObject[]> = this.getExtensions(customFilter);
        let listOfExtensionsBackend: Promise<Object> = this.configurationService.getProcessorExtensions();
        let combinedResult = Promise.all([listOfExtensionsInventory,listOfExtensionsBackend]).then (([listOfExtensionsInventory,listOfExtensionsBackend])=> {
             listOfExtensionsInventory.forEach ( ext => {
                if (listOfExtensionsBackend[ext.name]?.loaded) {
                    ext.loaded = listOfExtensionsBackend[ext.name].loaded;
                    let exts = _.values(listOfExtensionsBackend[ext.name].extensionEntries);
                    ext.extensionEntries = exts;
                } else {
                    ext.loaded = ExtensionStatus.UNKNOWN;
                }
            });
            return listOfExtensionsInventory;
        });
        return combinedResult;
    }

    async deleteExtension(app: IManagedObject): Promise<void> {
        let name = app.name;
        await this.modal.confirm(
            gettext('Delete extension'),
            this.translateService.instant(
                gettext(
                    `You are about to delete extension "{{name}}". Do you want to proceed?`
                ),
                { name }
            ),
            Status.DANGER,
            { ok: gettext('Delete'), cancel: gettext('Cancel') }
        );
        //TODO this needs to be changed: create 
        //await this.inventoryBinaryService.delete(app.id);
        await this.configurationService.deleteProcessorExtension(app.name);
        this.alertService.success(gettext('Extension deleted.'));
        this.appDeleted.emit(app);
    }

    updateUploadProgress(event): void {
        if (event.lengthComputable) {
            const currentProgress = this.progress.value;
            this.progress.next(currentProgress + (event.loaded / event.total) * (95 - currentProgress));
        }
    }

    async uploadExtension(archive: File, app: Partial<IManagedObject>): Promise<IManagedObjectBinary> {
        const result = (await this.inventoryBinaryService.create(archive, app)).data;

        return result
    }

    cancelExtensionCreation(app: Partial<IManagedObject>): void {
        if (app) {
            this.inventoryBinaryService.delete(app);
        }
    }
}