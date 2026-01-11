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
import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { IManagedObject } from '@c8y/client';

import { CoreModule } from '@c8y/ngx-components';
import { NODE3, SharedModule } from '../../shared';

interface ExtensionWithEntries extends IManagedObject {
  extensionEntries?: any[];
  external?: boolean;
  manifest?: {
    version?: string;
  };
}

@Component({
  selector: 'd11r-mapping-extension-properties',
  templateUrl: './extension-properties.component.html',
  standalone: true,
  imports: [CoreModule, SharedModule]
})
export class ExtensionPropertiesComponent implements OnInit {
  extension: ExtensionWithEntries;
  readonly LINK = `c8y-pkg-dynamic-mapper/${NODE3}/processorExtension`;

  constructor(private route: ActivatedRoute) {}

  ngOnInit(): void {
    const { extensions } = this.route.snapshot.data;

    if (!extensions || extensions.length === 0) {
      console.error('No extension data available');
      return;
    }

    this.extension = extensions[0];
  }

  get hasExtensionEntries(): boolean {
    return this.extension?.extensionEntries?.length > 0;
  }

  get extensionType(): string {
    return this.extension?.external ? 'External' : 'Internal';
  }
}
