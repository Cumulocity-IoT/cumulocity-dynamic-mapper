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
import { FormGroup } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { NODE3 } from '../../shared';
import { IManagedObject } from '@c8y/client';

@Component({
  selector: 'd11r-mapping-extension-properties',
  templateUrl: './extension-properties.component.html',
  standalone: false
})
export class ExtensionPropertiesComponent implements OnInit {
  extensionsEntryForm: FormGroup;
  extension: IManagedObject;
  LINK = `c8y-pkg-dynamic-mapper/${NODE3}/extension`;
  breadcrumbConfig: { icon: string; label: string; path: string };

  constructor(private route: ActivatedRoute) {}

  ngOnInit() {
    const {extensions} = this.route.snapshot.data;
    this.extension = extensions[0];
  }
}
