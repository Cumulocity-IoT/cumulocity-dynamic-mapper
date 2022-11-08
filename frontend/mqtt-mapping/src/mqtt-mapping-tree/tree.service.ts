import { Injectable } from '@angular/core';
import { FetchClient, InventoryService } from '@c8y/client';
import { AlertService } from '@c8y/ngx-components';
import { BASE_URL, PATH_MAPPING_TREE_ENDPOINT } from '../shared/util';

@Injectable({ providedIn: 'root' })
export class MappingTreeService {
    /** This will be used to build the inventory queries. */

    constructor(protected inventoryService: InventoryService,
        public alert: AlertService,
        private client: FetchClient,
    ) { }

    async loadMappingTree(): Promise<JSON> {
        const response = await this.client.fetch(`${BASE_URL}/${PATH_MAPPING_TREE_ENDPOINT}`, {
            headers: {
                'content-type': 'application/json'
            },
            method: 'GET',
        });

        if (response.status != 200) {
            return undefined;
        }

        return (await response.json()) as JSON;
    }
}