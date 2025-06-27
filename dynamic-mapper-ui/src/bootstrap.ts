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
import './polyfills';
import '@angular/compiler';

import { enableProdMode } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { BootstrapComponent, provideBootstrapMetadata } from '@c8y/ngx-components';
import { BootstrapMetaData } from '@c8y/bootstrap';
import { appConfig } from './app.config';

declare const __MODE__: string;
if (__MODE__ === 'production') {
  enableProdMode();
}

export function bootstrap(metadata: BootstrapMetaData) {
  appConfig.providers.push(...provideBootstrapMetadata(metadata));
  /**
   * Bootstrap your application with the BootstrapComponent which will use the `<c8y-bootstrap>`
   * component to initialize the root application. Alternatively you can bootstrap
   * a component of your choice and include that tag into its template or only reuse the given components
   */
  return bootstrapApplication(BootstrapComponent, appConfig).catch(err => console.log(err));
}