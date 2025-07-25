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
import { Injectable } from '@angular/core';
import { CanActivate } from '@angular/router';
import { ApplicationService } from '@c8y/client';

@Injectable({ providedIn: 'root' })
export class OverviewGuard implements CanActivate {
  private static readonly APPLICATION = 'dynamic-mapper-service';

  private activateOverviewNavigationPromise: Promise<boolean>;

  constructor(private applicationService: ApplicationService) {}

  canActivate(): Promise<boolean> {
    if (!this.activateOverviewNavigationPromise) {
      this.activateOverviewNavigationPromise = this.applicationService
        .isAvailable(OverviewGuard.APPLICATION)
        .then((result) => {
          if (!(result && result.data)) {
            console.error('Dynamic Mapper Microservice not subscribed!');
          }

          return result && result.data;
        });
    }

    return this.activateOverviewNavigationPromise;
  }
}
