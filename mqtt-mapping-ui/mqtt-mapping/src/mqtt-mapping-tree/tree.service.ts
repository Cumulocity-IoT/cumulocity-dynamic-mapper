import { Injectable } from '@angular/core';
import { FetchClient, InventoryService } from '@c8y/client';
import { AlertService } from '@c8y/ngx-components';
import { BASE_URL, PATH_MAPPING_TREE_ENDPOINT, whatIsIt } from '../shared/util';
import * as _ from 'lodash';

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
        let tree = (await response.json()) as JSON
        // ignore first level of the object, as it does not contain any information
        if (tree?.['childNodes']) {
            tree = tree?.['childNodes']
        }
        this.clean(tree, ['level', 'depthIndex', 'preTreeNode']);
        return tree;
    }


    clean(tree: JSON, removeSet: string[]): void {
        let t = whatIsIt(tree);
        if (t == 'Object') {
            removeSet.forEach(property => {
                _.unset(tree, property);
            });
            for (const key in tree) {
                if (Object.prototype.hasOwnProperty.call(tree, key)) {
                    const element = tree[key];
                    this.clean(tree[key], removeSet);
                }
            }
        } else if (t == 'Array') {
            for (var item in tree) {
                //console.log("New items:", item, whatIsIt(item));
                this.clean(tree[item], removeSet);
            }
        }
    }
}
