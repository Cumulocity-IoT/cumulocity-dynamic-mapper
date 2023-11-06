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
import { Injectable } from "@angular/core";
import { FetchClient, InventoryService } from "@c8y/client";
import { AlertService } from "@c8y/ngx-components";
import { BASE_URL, PATH_MAPPING_TREE_ENDPOINT, whatIsIt } from "../shared/util";
import * as _ from "lodash";

@Injectable({ providedIn: "root" })
export class MappingTreeService {
  /** This will be used to build the inventory queries. */

  constructor(
    protected inventoryService: InventoryService,
    public alert: AlertService,
    private client: FetchClient
  ) {}

  async loadMappingTree(): Promise<JSON> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_TREE_ENDPOINT}`,
      {
        headers: {
          "content-type": "application/json",
        },
        method: "GET",
      }
    );

    if (response.status != 200) {
      return undefined;
    }
    let tree = (await response.json()) as JSON;
    //ignore first level of the object, as it does not contain any information
    if (tree?.["childNodes"]) {
      tree = tree?.["childNodes"];
    }
    this.clean(tree, ["level", "depthIndex", "parentNode", , "absolutePath"]);
    return tree;
  }

  clean(tree: JSON, removeSet: string[]): void {
    let t = whatIsIt(tree);


    // remove properties that should not be displayed
    if (t == "Object") {
      removeSet.forEach((property) => {
        _.unset(tree, property);
      });
      if ( 'childNodes' in tree) {
        const childNodes = tree['childNodes'] as any
        _.unset(tree, 'childNodes');
        for (const key in childNodes) {
          if (Object.prototype.hasOwnProperty.call(childNodes, key)) {
            _.set(tree, key, childNodes[key]);
          }
        }
      }
      for (const key in tree) {
        if (Object.prototype.hasOwnProperty.call(tree, key)) {
          const element = tree[key];
          this.clean(tree[key], removeSet);
        }
      }
    } else if (t == "Array") {
      for (var item in tree) {
        //console.log("New items:", item, whatIsIt(item));
        this.clean(tree[item], removeSet);
      }
    }
  }
}
