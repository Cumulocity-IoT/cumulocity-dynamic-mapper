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
import { Component, OnInit } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import {
  IManagedObject,
} from '@c8y/client';
import {
  gettext,
} from '@c8y/ngx-components';

import { ExtensionService } from './extension.service';

@Component({
  selector: 'mapping-extension-properties',
  templateUrl: './extension-properties.component.html'
})
export class ExtensionPropertiesComponent implements OnInit {
  extensionsEntryForm: FormGroup;
  extension: IManagedObject;

  isLoading: boolean = true;

  breadcrumbConfig: { icon: string; label: string; path: string };

  constructor(
    private activatedRoute: ActivatedRoute,
    private formBuilder: FormBuilder,
    private extensionService: ExtensionService,
  ) { 
    console.log("Props loaded---------");
  }

  async ngOnInit() {
    await this.refresh();
  }

  async refresh() {
    await this.load();
    this.setBreadcrumbConfig();
  }

  async load() {
    this.isLoading = true;
    this.initForm();
    await this.loadExtension();
    this.isLoading = false;
  }

  async loadExtension() {
    const { id } = this.activatedRoute.snapshot.params;
    let filter = { id: id }
    let result = await this.extensionService.getExtensionsEnriched(filter);
    let ext = result[0];
    this.extension = result[0];
    this.extensionsEntryForm.patchValue({ ...this.extension.extensionEntries });

    this.extension.extensionEntries?.forEach(entry => {
      const extensionEntriesForm = this.formBuilder.group({
        name: [entry.name],
        event: [entry.event],
        message:[entry.message]
      });
      this.extensionEntries.push(extensionEntriesForm);
    })

  }

  private initForm(): void {
    this.extensionsEntryForm = this.formBuilder.group({
      extensionEntries: this.formBuilder.array([])
    });

  }

  get extensionEntries() {
    return this.extensionsEntryForm.controls["extensionEntries"] as FormArray;
  }

  private setBreadcrumbConfig() {
    this.breadcrumbConfig = {
      icon: 'c8y-modules',
      label: gettext('Extensions'),
      path: 'mqtt-mapping/extensions'
    };
  }

}
