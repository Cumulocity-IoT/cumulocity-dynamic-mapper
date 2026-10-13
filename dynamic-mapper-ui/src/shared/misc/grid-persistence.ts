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
import { inject, Provider } from '@angular/core';
import { Router } from '@angular/router';
import {
  DATA_GRID_CONFIGURATION_CONTEXT_PROVIDER,
  DATA_GRID_CONFIGURATION_STRATEGY,
  GridConfigContextProvider,
  UserPreferencesConfigurationStrategy
} from '@c8y/ngx-components';

const KEY_PREFIX = 'dynamic-mapper.grid.';

/**
 * Persists a `c8y-data-grid`'s column sort order, column filters and page size as a Cumulocity
 * user preference, so they survive navigation and reloads. Add the result to the `providers` of the
 * component that hosts the grid.
 *
 * @param key Grid identifier, or a function deriving it from the router (for components shared by
 *            several routes, e.g. inbound/outbound). It is resolved lazily, when the grid initializes.
 */
export function provideGridPersistence(key: string | ((url: string) => string)): Provider[] {
  return [
    {
      provide: DATA_GRID_CONFIGURATION_CONTEXT_PROVIDER,
      useFactory: (): GridConfigContextProvider => {
        const router = inject(Router);
        return {
          getGridConfigContext: () => ({
            key: KEY_PREFIX + (typeof key === 'string' ? key : key(router.url)),
            // free-text search is not part of the config; column order/visibility are not user-configurable here
            configFilter: { order: false, visibility: false }
          })
        };
      }
    },
    { provide: DATA_GRID_CONFIGURATION_STRATEGY, useClass: UserPreferencesConfigurationStrategy }
  ];
}
