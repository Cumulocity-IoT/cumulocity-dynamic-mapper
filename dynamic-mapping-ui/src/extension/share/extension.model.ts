import { inject } from "@angular/core";
import { ResolveFn } from "@angular/router";
import { IManagedObject } from "@c8y/client";
import { ExtensionService } from "../extension.service";

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
export enum ERROR_TYPE {
  TYPE_VALIDATION = 'TYPE_VALIDATION',
  ALREADY_SUBSCRIBED = 'ALREADY_SUBSCRIBED',
  INTERNAL_ERROR = 'INTERNAL_ERROR',
  NO_MANIFEST_FILE = 'NO_MANIFEST_FILE',
  INVALID_PACKAGE = 'INVALID_PACKAGE',
  INVALID_APPLICATION = 'INVALID_APPLICATION'
}

export const extensionResolver: ResolveFn<IManagedObject[]> = (route) => {
  const extensionService = inject(ExtensionService);
  const id = route.paramMap.get('id');
  return extensionService.getExtensionsEnriched(id);
};
