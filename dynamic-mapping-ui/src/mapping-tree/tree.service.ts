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
import { HttpStatusCode } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { FetchClient, InventoryService } from '@c8y/client';
import { AlertService } from '@c8y/ngx-components';
import * as _ from 'lodash';
import { isTypeOf } from '../mapping/shared/util';
import { BASE_URL, PATH_MAPPING_TREE_ENDPOINT } from '../shared';

@Injectable({ providedIn: 'root' })
export class MappingTreeService {
  /** This will be used to build the inventory queries. */

  constructor(
    protected inventoryService: InventoryService,
    alert: AlertService,
    private client: FetchClient
  ) {}

  protected JSONATA = require('jsonata');

  async loadMappingTree(): Promise<JSON> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_TREE_ENDPOINT}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        method: 'GET'
      }
    );

    if (response.status != HttpStatusCode.Ok) {
      return undefined;
    }
    let tree = (await response.json()) as JSON;
    // ignore first level of the object, as it does not contain any information
    // if (tree?.["childNodes"]) {
    //   tree = tree?.["childNodes"];
    // }
    // this.clean(tree, [
    //   "level",
    //   "depthIndex",
    //   "parentNode",
    //   "childNodes",
    //   "absolutePath",
    // ]);
    tree = this.cleanJSONata(tree);
    return tree;
  }

  clean(tree: JSON, removeSet: string[]): void {
    const typeTree = isTypeOf(tree);

    // remove properties that should not be displayed
    if (typeTree == 'Object') {
      if ('absolutePath' in tree) {
        const absolutePath = tree['absolutePath'] as any;
        _.unset(tree, 'absolutePath');
        tree[absolutePath] = tree['childNodes'];
      }
      removeSet.forEach((property) => {
        _.unset(tree, property);
      });
      // // if tree contains childNodes as a property, promote the childes one hierarchy up
      // if ("childNodes" in tree) {
      //   const childNodes = tree["childNodes"] as any;
      //   _.unset(tree, "childNodes");
      //   for (const key in childNodes) {
      //     if (Object.prototype.hasOwnProperty.call(childNodes, key)) {
      //       _.set(tree, key, childNodes[key]);
      //     }
      //   }
      // }

      for (const key in tree) {
        if (Object.prototype.hasOwnProperty.call(tree, key)) {
          const typeItem = isTypeOf(tree[key]);
          const tempItem = tree[key];
          if (typeItem == 'Array' && (tempItem as any[]).length == 1) {
            _.unset(tree, key);
            tree[key] = tempItem[0];
            this.clean(tree[key], removeSet);
          } else {
            this.clean(tree[key], removeSet);
          }
        }
      }
    } else if (typeTree == 'Array') {
      for (const item in tree) {
        // console.log("New items:", item, whatIsIt(item));
        this.clean(tree[item], removeSet);
      }
    }
  }

  cleanJSONata(tree: JSON): JSON {
    const filter = `
    (
      $childNodes := function($node) {
          $node.* ~> $map(function($v, $i) {
               $exists($v.mapping)  ? {$v.absolutePath : $v.mapping} : { $v.absolutePath : $childNodes($v.childNodes) } 
          }) 
      };
      childNodes ~> $childNodes($)
  )
    `;
    // console.log("TREE:", JSON.stringify(tree, undefined, 2));

    const expression = this.JSONATA(filter);
    return expression.evaluate(tree) as JSON;
  }
}
