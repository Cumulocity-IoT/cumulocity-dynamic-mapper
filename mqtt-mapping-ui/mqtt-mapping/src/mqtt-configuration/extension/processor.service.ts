
import { EventEmitter, Injectable } from '@angular/core';
import {
    FetchClient, IFetchOptions, IManagedObject, IManagedObjectBinary, InventoryBinaryService, InventoryService,
    IResultList
} from '@c8y/client';

import {
    AlertService, gettext, ModalService, Status
} from '@c8y/ngx-components';

import { TranslateService } from '@ngx-translate/core';

import { BehaviorSubject } from 'rxjs';
import { Extension } from '../../shared/mapping.model';
import { PROCESSOR_EXTENSION_TYPE } from '../../shared/util';
import { BrokerConfigurationService } from '../broker-configuration.service';

@Injectable({ providedIn: 'root' })
export class ProcessorService {

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

    getExtensions(customFilter: any = {}): Promise<IResultList<IManagedObject>> {
        const filter: object = {
            pageSize: 100,
            withTotalPages: true,
            fragmentType: PROCESSOR_EXTENSION_TYPE,
        };
        Object.assign(filter, customFilter);
        const query: object = {
            //   fragmentType: 'pas_extension',
        };
        let result;
        if (Object.keys(customFilter).length == 0) {
            result = this.inventoryService.list(filter);
        } else {
            result = this.inventoryService.listQuery(query, filter);
        }
        return result;
    }
    async getProcessorExtensions(customFilter: any = {}): Promise<IManagedObject[]> {
        let listOfExtensionsInventory: Promise<IResultList<IManagedObject>> = this.getExtensions(customFilter);
        let listOfExtensionsBackend: Promise<Object> = this.configurationService.getProcessorExtensions();
        let combinedResult = Promise.all([listOfExtensionsInventory,listOfExtensionsBackend]).then (([listOfExtensionsInventory,listOfExtensionsBackend])=> {
            let extensionsInventory = listOfExtensionsInventory.data;
            extensionsInventory.forEach ( ext => {
                     ext.loaded = listOfExtensionsBackend[ext.name].loaded;
            });
            return extensionsInventory;
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