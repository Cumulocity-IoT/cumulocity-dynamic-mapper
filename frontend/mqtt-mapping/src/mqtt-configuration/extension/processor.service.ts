
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
        private fetchClient: FetchClient
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
        let extensions = (await this.getExtensions(customFilter)).data;
        extensions.forEach (async ext => {
            ext.loaded = (await this.configurationService.getProcessorExtension(ext.name)).loadedSuccessfully;
        })
        // return (await this.getExtensions(customFilter)).data;
        return extensions;
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
        await this.inventoryBinaryService.delete(app.id);
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