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
import { AfterViewInit, Component } from '@angular/core';
import { FormGroup } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ExtensionService } from '../extension.service';
import { NODE3 } from '../../shared';

@Component({
  selector: 'd11r-mapping-extension-properties',
  templateUrl: './extension-properties.component.html'
})
export class ExtensionPropertiesComponent implements AfterViewInit {
  extensionsEntryForm: FormGroup;
  extension = {
    name: undefined
  };
  isLoading: boolean = true;
  LINK = `sag-ps-pkg-dynamic-mapping/${NODE3}/extension`;
  breadcrumbConfig: { icon: string; label: string; path: string };

  constructor(
    private activatedRoute: ActivatedRoute,
    private extensionService: ExtensionService
  ) {}

  async refresh() {
    await this.load();
  }

  async load() {
    this.isLoading = true;
    await this.loadExtension();
    this.isLoading = false;
  }

  async loadExtension() {
    const { id } = this.activatedRoute.snapshot.params;
    const result = await this.extensionService.getExtensionsEnriched(id);
    this.extension = result[0] as any;
  }

  ngAfterViewInit() {
    setTimeout(async () => {
      await this.load();
    }, 0);
  }
}
