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
import { Injectable } from "@angular/core";
import { IAlarm, IResult, AlarmService, IFetchResponse } from "@c8y/client";
import * as _ from 'lodash';
import { ProcessingContext } from "../processor/prosessor.model";

@Injectable({ providedIn: 'root' })
export class FacadeAlarmService {
  constructor(
    private alarm: AlarmService) {
  }

  public create(alarm: IAlarm, context: ProcessingContext): Promise<IResult<IAlarm>> {
    if (context.sendPayload) {
      return this.alarm.create(alarm);
    } else {
      let copyAlarm = {
        ...alarm,
        id: Math.floor(100000 + Math.random() * 900000).toString(),
        lastUpdated: new Date().toISOString(),
      }
      const promise = Promise.resolve({
        data: copyAlarm,
        res: {status: 200} as IFetchResponse
      });
      return promise;
    }
  }
}