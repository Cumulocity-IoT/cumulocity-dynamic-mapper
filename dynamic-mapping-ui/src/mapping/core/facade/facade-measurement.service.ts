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
  IMeasurement,
  IResult,
  MeasurementService
} from '@c8y/client';
import { ProcessingContext } from '../processor/processor.model';
import { HttpStatusCode } from '@angular/common/http';
import { randomIdAsString } from '../../../mapping/shared/util';

@Injectable({ providedIn: 'root' })
export class FacadeMeasurementService {
  constructor(private measurement: MeasurementService) {}

  create(
    measurement: IMeasurement,
    context: ProcessingContext
  ): Promise<IResult<IMeasurement>> {
    if (context.sendPayload) {
      return this.measurement.create(measurement);
    } else {
      const copyMeasurement: IMeasurement = {
        ...measurement,
        id: randomIdAsString(),
        lastUpdated: new Date().toISOString()
      };
      const promise = Promise.resolve({
        data: copyMeasurement,
        res: { status: HttpStatusCode.Ok } as IFetchResponse
      });
      return promise;
    }
  }
}
