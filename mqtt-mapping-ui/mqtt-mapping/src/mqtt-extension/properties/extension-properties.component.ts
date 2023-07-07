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
import { Component, OnInit } from "@angular/core";
import { FormGroup } from "@angular/forms";
import { ActivatedRoute } from "@angular/router";
import { IManagedObject } from "@c8y/client";
import { gettext } from "@c8y/ngx-components";

import { ExtensionService } from "../share/extension.service";

@Component({
  selector: "mapping-extension-properties",
  templateUrl: "./extension-properties.component.html",
})
export class ExtensionPropertiesComponent implements OnInit {
  extensionsEntryForm: FormGroup;
  extension: IManagedObject;

  isLoading: boolean = true;
  isCollapsed: any = {};

  breadcrumbConfig: { icon: string; label: string; path: string };

  constructor(
    private activatedRoute: ActivatedRoute,
    private extensionService: ExtensionService
  ) {}

  async ngOnInit() {
    await this.refresh();
  }

  async refresh() {
    await this.load();
    this.setBreadcrumbConfig();
  }

  async load() {
    this.isLoading = true;
    await this.loadExtension();
    this.isLoading = false;
  }

  async loadExtension() {
    const { id } = this.activatedRoute.snapshot.params;
    let result = await this.extensionService.getExtensionsEnriched(id);
    this.extension = result[0];
    // let copy = {
    //   name: this.extension.extensionEntries[0].name + "copy",
    //   event: this.extension.extensionEntries[0].event + "copy",
    //   message: this.extension.extensionEntries[0].message + "copy"
    // };
    // this.extension.extensionEntries.push(copy);
    this.extension.extensionEntries?.forEach((entry) => {
      this.isCollapsed[entry.name] = true;
    });
  }

  private setBreadcrumbConfig() {
    this.breadcrumbConfig = {
      icon: "c8y-modules",
      label: gettext("Extensions"),
      path: "sag-ps-pkg-mqtt-mapping/extensions",
    };
  }
}
