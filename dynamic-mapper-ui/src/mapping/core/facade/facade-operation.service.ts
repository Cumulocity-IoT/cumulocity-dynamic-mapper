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
import {
  IFetchResponse,
  IOperation,
  IResult,
  OperationService
} from '@c8y/client';
import { ProcessingContext } from '../processor/processor.model';
import { HttpStatusCode } from '@angular/common/http';
import { randomIdAsString } from '../../../mapping/shared/util';

@Injectable({ providedIn: 'root' })
export class FacadeOperationService {
  constructor(private operation: OperationService) {}

  create(
    operation: IOperation,
    context: ProcessingContext
  ): Promise<IResult<IOperation>> {
    if (context.sendPayload) {
      return this.operation.create(operation);
    } else {
      const copyOperation = {
        ...operation,
        id: randomIdAsString(),
        lastUpdated: new Date().toISOString()
      };
      const promise = Promise.resolve({
        data: copyOperation,
        res: { status: HttpStatusCode.Ok } as IFetchResponse
      });
      return promise;
    }
  }
}
