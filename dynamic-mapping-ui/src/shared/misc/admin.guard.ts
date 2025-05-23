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
import { SharedService } from '../service/shared.service';

@Injectable({ providedIn: 'root' })
export class AdminGuard implements CanActivate {
  private adminPromise: Promise<boolean>;

  constructor(private sharedService: SharedService) {}

  canActivate(): Promise<boolean> {
    this.adminPromise = this.sharedService.getFeatures().then((conf) => {
      // console.log(
      //   "User has externalExtensionEnabled:",
      //   conf.userHasMappingAdminRole
      // );
      return conf.userHasMappingAdminRole;
    });

    return this.adminPromise;
  }
}
